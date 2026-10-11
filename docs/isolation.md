# Database Isolation Levels Lab (`docs/isolation.md`)

This document records the exact behavior, SQL commands, and observed output for PostgreSQL isolation levels across concurrent transactions.

---

## Experiment 1: Read Committed (PostgreSQL Default) — Non-Repeatable Read

In `READ COMMITTED`, each statement inside a transaction sees a snapshot of data committed **before that statement began**, not when the transaction started.

### Setup
```sql
-- Seed initial state in psql
UPDATE accounts SET balance = 50000 WHERE id = 1; -- ₹500.00
```

### Execution Trace
| Time | Session A (`psql` 1) | Session B (`psql` 2) | Observation / State |
| :--- | :--- | :--- | :--- |
| $T_1$ | `BEGIN;` | | Session A starts transaction |
| $T_2$ | `SELECT balance FROM accounts WHERE id = 1;` <br> *(Returns 50000)* | | Initial read |
| $T_3$ | | `BEGIN;` <br> `UPDATE accounts SET balance = 30000 WHERE id = 1;` <br> `COMMIT;` | Session B modifies balance to ₹300 and commits |
| $T_4$ | `SELECT balance FROM accounts WHERE id = 1;` <br> *(Returns 30000)* | | **Non-Repeatable Read observed!** |
| $T_5$ | `COMMIT;` | | Session A finishes |

### Finding
Inside Session A, running the exact same `SELECT` twice within a single transaction returned two different balances (`50000` then `30000`). This demonstrates why application-level balance checks outside row locks are unsafe in `READ COMMITTED`.

---

## Experiment 2: Repeatable Read — Snapshot Isolation

In `REPEATABLE READ`, a transaction sees a snapshot of the database taken at the start of its **first non-transaction-control statement**.

### Execution Trace
| Time | Session A (`psql` 1) | Session B (`psql` 2) | Observation / State |
| :--- | :--- | :--- | :--- |
| $T_1$ | `BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ;` | | Session A starts in Repeatable Read |
| $T_2$ | `SELECT balance FROM accounts WHERE id = 1;` <br> *(Returns 30000)* | | Snapshot established at $T_2$ |
| $T_3$ | | `BEGIN;` <br> `UPDATE accounts SET balance = 10000 WHERE id = 1;` <br> `COMMIT;` | Session B updates balance to ₹100 and commits |
| $T_4$ | `SELECT balance FROM accounts WHERE id = 1;` <br> *(Returns 30000)* | | **Snapshot preserved!** Does not see Session B's commit |
| $T_5$ | `COMMIT;` | | Session A commits |
| $T_6$ | `SELECT balance FROM accounts WHERE id = 1;` <br> *(Returns 10000)* | | Fresh statement outside transaction sees new value |

### Finding
Session A is insulated from concurrent commits made by Session B. This property is essential for our **nightly reconciliation job**, ensuring that active transfers happening mid-audit do not cause reconciliation reports to disagree with themselves.

---

## Experiment 3: Serializable — Serialization Anomaly Detection

`SERIALIZABLE` guarantees that transactions execute as if they ran sequentially one after the other.

### Execution Trace
| Time | Session A (`psql` 1) | Session B (`psql` 2) | Output / Result |
| :--- | :--- | :--- | :--- |
| $T_1$ | `BEGIN TRANSACTION ISOLATION LEVEL SERIALIZABLE;` | `BEGIN TRANSACTION ISOLATION LEVEL SERIALIZABLE;` | Both start |
| $T_2$ | `SELECT balance FROM accounts WHERE id = 1;` *(Returns 10000)* | `SELECT balance FROM accounts WHERE id = 1;` *(Returns 10000)* | Both read same row |
| $T_3$ | `UPDATE accounts SET balance = balance - 5000 WHERE id = 1;` | | Session A executes update (uncommitted) |
| $T_4$ | | `UPDATE accounts SET balance = balance - 5000 WHERE id = 1;` | **Session B BLOCKS (waiting for lock)** |
| $T_5$ | `COMMIT;` | | Session A commits successfully |
| $T_6$ | | *Session B unblocks and immediately errors:* | `ERROR: 40001: could not serialize access due to concurrent update` |

### Finding
In `SERIALIZABLE`, when two transactions read the same data and then attempt to update it, PostgreSQL refuses to allow the second transaction to commit, aborting it with error `40001`. 

### Why we do NOT use Serializable for transfers:
Under high traffic (e.g., 100 concurrent payments), `SERIALIZABLE` causes massive abort rates, requiring complex application retry loops and thrashing system resources. Instead, `READ COMMITTED` with `SELECT ... FOR UPDATE` serializes access gracefully with row locks without aborting.

---

## Experiment 4: Repeatable Read with a Multi-Table Join

Testing whether snapshot consistency holds when querying across multiple related tables (`accounts` joined with `ledger_entries`).

### Query
```sql
BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ;

SELECT a.id, a.account_number, a.balance, COUNT(le.id) AS total_entries
FROM accounts a
LEFT JOIN ledger_entries le ON a.id = le.account_id
WHERE a.id = 1
GROUP BY a.id, a.account_number, a.balance;
```

### Observation
Even if Session B inserts 100 new ledger entries and updates the account balance while Session A is running, Session A's join query returns the exact counts and balance corresponding to the snapshot taken at the start of Session A. All joined tables reflect a single, coherent point in time.
