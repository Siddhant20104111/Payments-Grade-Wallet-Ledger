# Milestone 1: Database Schema & Double-Entry Ledger Design

## 1. Requirements Noun Analysis

To design a robust relational schema, we extract every noun from the business requirements and evaluate whether it justifies a dedicated table.

| Noun | Table Created? | Rationale |
| :--- | :--- | :--- |
| **User** | **Yes (`users`)** | Core identity entity storing personal data (name, email, phone) and KYC status. |
| **Wallet / Account** | **Yes (`accounts`)** | Financial container holding balances. Unifies user wallets and system clearing accounts under one domain model. |
| **Transfer** | **Yes (`transfers`)** | Represents the business event (intent, type, amount, status, timestamps, idempotency association). |
| **Ledger Entry** | **Yes (`ledger_entries`)** | Immutable double-entry records. Every transfer generates $\ge 2$ entries (Debits and Credits) that sum to zero. |
| **Idempotency Key** | **Yes (`idempotency_keys`)** | Ensures safe retries by associating a client-provided key and request hash with cached HTTP responses. |
| **Audit Log** | **Yes (`audit_logs`)** | Tracks administrative state transitions (e.g., fraud hold/release) with actor and timestamp. |
| **Risk Rule** | **Yes (`risk_rules`)** | Configurable fraud detection thresholds stored in the database rather than hardcoded in application logic. |
| **Risk Flag** | **Yes (`risk_flags`)** | Flags raised against suspicious transfers along with AI-generated risk evaluations. |
| **Reconciliation Run** | **Yes (`reconciliation_runs`)** | Records nightly ledger audit executions, status, and summary counts. |
| **Reconciliation Issue**| **Yes (`reconciliation_issues`)**| Granular record of specific discrepancies found during nightly reconciliation audits. |
| **Job Lock** | **Yes (`job_locks`)** | Distributed single-instance execution lock preventing concurrent background jobs. |
| **Currency** | No (Attribute) | System operates exclusively in INR; modeled as a column constraint (`currency = 'INR'`). |

---

## 2. Entity Relationships & Cardinality

1. **`users` (1) ── (M) `accounts`**:
   - A single user can hold multiple wallets (e.g., Main Wallet, Savings Wallet).
   - System accounts (e.g., `CASH_IN_CLEARING`, `FEE_REVENUE`) have a `NULL` `user_id`.
   - Cardinality: **1 : M (Zero or more)**. Foreign key placed on `accounts.user_id`.

2. **`transfers` (1) ── (M) `ledger_entries`**:
   - Every financial transfer decomposes into 2 or more debit/credit ledger lines.
   - Cardinality: **1 : M ($\ge 2$)**. Foreign key placed on `ledger_entries.transfer_id`.

3. **`accounts` (1) ── (M) `ledger_entries`**:
   - An account receives multiple historical debit and credit entries over time.
   - Cardinality: **1 : M**. Foreign key placed on `ledger_entries.account_id`.

4. **`users` (1) ── (M) `idempotency_keys`**:
   - A user submits requests with unique idempotency keys.
   - Cardinality: **1 : M**. Composite unique constraint on `(user_id, idempotency_key)`.

---

## 3. Entity-Relationship (ER) Diagram

```mermaid
erDiagram
    users ||--o{ accounts : "owns (0..N)"
    users ||--o{ idempotency_keys : "issues (0..N)"
    accounts ||--o{ ledger_entries : "has history (0..N)"
    transfers ||--|{ ledger_entries : "produces (2..N)"
    transfers ||--o{ audit_logs : "has audit trail (0..N)"
    transfers ||--o{ risk_flags : "may trigger (0..N)"
    reconciliation_runs ||--o{ reconciliation_issues : "records (0..N)"

    users {
        BIGINT id PK
        VARCHAR name
        VARCHAR email UK
        VARCHAR phone UK
        VARCHAR kyc_status
        TIMESTAMPTZ created_at
        TIMESTAMPTZ updated_at
    }

    accounts {
        BIGINT id PK
        BIGINT user_id FK "NULL for system accounts"
        VARCHAR account_number UK
        VARCHAR type "USER or SYSTEM"
        VARCHAR name "e.g. MAIN, CASH_IN_CLEARING"
        VARCHAR currency "INR"
        BIGINT balance "in paise"
        TIMESTAMPTZ created_at
        TIMESTAMPTZ updated_at
    }

    transfers {
        BIGINT id PK
        BIGINT user_id FK "initiator"
        VARCHAR transfer_type "TOP_UP, PEER_TRANSFER, WITHDRAWAL, FEE, REVERSAL"
        BIGINT amount "in paise (> 0)"
        VARCHAR currency "INR"
        VARCHAR status "PENDING, COMPLETED, HELD, REJECTED, FAILED"
        VARCHAR description
        BIGINT parent_transfer_id FK "for reversals"
        TIMESTAMPTZ created_at
        TIMESTAMPTZ updated_at
    }

    ledger_entries {
        BIGINT id PK
        BIGINT transfer_id FK
        BIGINT account_id FK
        VARCHAR entry_type "DEBIT or CREDIT"
        BIGINT amount "positive in paise (> 0)"
        VARCHAR currency "INR"
        BIGINT balance_after "historical snapshot in paise"
        TIMESTAMPTZ created_at
    }

    idempotency_keys {
        BIGINT id PK
        BIGINT user_id FK
        VARCHAR key
        VARCHAR request_hash
        VARCHAR status "IN_PROGRESS, COMPLETED, FAILED"
        INTEGER response_code
        TEXT response_body
        TIMESTAMPTZ created_at
        TIMESTAMPTZ updated_at
    }

    risk_rules {
        BIGINT id PK
        VARCHAR rule_code UK
        VARCHAR description
        BIGINT threshold_value
        BOOLEAN is_active
        TIMESTAMPTZ created_at
    }

    risk_flags {
        BIGINT id PK
        BIGINT transfer_id FK
        VARCHAR rule_code
        VARCHAR ai_evaluation "likely_ok, review, likely_fraud"
        TEXT ai_explanation
        TIMESTAMPTZ created_at
    }

    audit_logs {
        BIGINT id PK
        BIGINT transfer_id FK
        VARCHAR action "HELD, RELEASED, REJECTED"
        VARCHAR performed_by
        TEXT reason
        TIMESTAMPTZ created_at
    }

    job_locks {
        VARCHAR job_name PK
        VARCHAR locked_by
        TIMESTAMPTZ locked_at
    }

    reconciliation_runs {
        BIGINT id PK
        TIMESTAMPTZ started_at
        TIMESTAMPTZ finished_at
        VARCHAR status "SUCCESS, FAILED, MISMATCH_DETECTED"
        BIGINT total_transfers_checked
        BIGINT total_discrepancies_found
    }

    reconciliation_issues {
        BIGINT id PK
        BIGINT run_id FK
        VARCHAR check_type
        BIGINT entity_id
        TEXT expected_value
        TEXT actual_value
        TIMESTAMPTZ detected_at
    }
```

---

## 4. Key Architectural Decisions & Trade-Offs

### 1. Stored Balances vs. Computed Balances (Hybrid Pattern)
* **The Decision**: We store a `balance` column on `accounts` and maintain it atomically within the transfer transaction alongside inserting `ledger_entries`.
* **Rationale**: Computing balances on the fly via `SUM(...)` over millions of ledger entries incurs an $O(N)$ table scan on every wallet query or checkout step, crippling system throughput.
* **Guarantee**: The stored balance is treated as a **hot cached projection** of the ledger. Our nightly reconciliation engine validates that every stored balance mathematically matches $\sum \text{Credits} - \sum \text{Debits}$.

### 2. Unified Account Model
* **The Decision**: User wallets and platform clearing accounts (`CASH_IN_CLEARING`, `CASH_OUT_CLEARING`, `FEE_REVENUE`) are stored in the same `accounts` table.
* **Rationale**: Preserves the **Conservation of Money Principle** ($\sum \text{Debits} = \sum \text{Credits}$). Top-ups and withdrawals do not create or destroy money; they transfer funds between external gateway clearing accounts and user wallets using the exact same atomic transfer workflow.

### 3. Surrogate Primary Keys vs. Natural Keys
* **The Decision**: All primary keys are `BIGINT GENERATED ALWAYS AS IDENTITY`.
* **Rationale**: User attributes such as email addresses, phone numbers, and bank account numbers are subject to change, privacy regulations (e.g., GDPR right to be forgotten), or reassignment by telecom providers. Surrogate keys are immutable, compact (8 bytes), and guarantee monotonic index ordering.

### 4. Database-Level Invariant Enforcement
* **Non-Negative Balance**: `CHECK (type = 'SYSTEM' OR balance >= 0)` ensures user wallets can never be overdrawn under any circumstance.
* **Positive Ledger Amounts**: `CHECK (amount > 0)` and `CHECK (entry_type IN ('DEBIT', 'CREDIT'))` eliminate sign-confusion bugs.
* **Append-Only Immutability**: PostgreSQL database triggers block `UPDATE` and `DELETE` operations on `ledger_entries`.
