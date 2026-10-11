-- =============================================================================
-- High-Performance PostgreSQL Seed Script: 1,000 Users, 2,000 Wallets, 100,000 Transfers
-- Executable in under 10 seconds via pure relational generate_series
-- =============================================================================

BEGIN;

-- Temporarily bypass the mutation trigger during bulk load for maximum throughput
ALTER TABLE ledger_entries DISABLE TRIGGER trg_prevent_ledger_update;

\echo 'Seeding 1,000 users...'
INSERT INTO users (name, email, phone, kyc_status, created_at, updated_at)
SELECT
    'User ' || i,
    'user_' || i || '@seedwallet.com',
    '+91' || lpad(i::text, 10, '9'),
    'VERIFIED',
    NOW() - (i || ' minutes')::interval,
    NOW()
FROM generate_series(1, 1000) AS i
ON CONFLICT (email) DO NOTHING;

\echo 'Seeding 2,000 wallets (2 per user: Main & Savings)...'
INSERT INTO accounts (user_id, account_number, type, name, currency, balance, created_at, updated_at)
SELECT
    u.id,
    'W-' || u.id || '-MAIN',
    'USER',
    'Main Wallet',
    'INR',
    500000, -- ₹5,000 initial balance in paise
    NOW(),
    NOW()
FROM users u
WHERE u.email LIKE '%@seedwallet.com'
ON CONFLICT (account_number) DO NOTHING;

INSERT INTO accounts (user_id, account_number, type, name, currency, balance, created_at, updated_at)
SELECT
    u.id,
    'W-' || u.id || '-SAVINGS',
    'USER',
    'Savings Wallet',
    'INR',
    1000000, -- ₹10,000 initial balance in paise
    NOW(),
    NOW()
FROM users u
WHERE u.email LIKE '%@seedwallet.com'
ON CONFLICT (account_number) DO NOTHING;

\echo 'Seeding 100,000 balanced double-entry transfers...'
-- Insert 100,000 transfer records
WITH inserted_transfers AS (
    INSERT INTO transfers (user_id, idempotency_key, transfer_type, amount, currency, status, description, created_at, updated_at)
    SELECT
        ((s % 1000) + 1),
        'SEED-KEY-' || s,
        'PEER_TRANSFER',
        ((s % 500) + 1) * 100, -- amounts between ₹1.00 and ₹500.00 in paise
        'INR',
        'COMPLETED',
        'Bulk Seed Payment #' || s,
        NOW() - ((100000 - s) || ' seconds')::interval,
        NOW()
    FROM generate_series(1, 100000) AS s
    RETURNING id, user_id, amount, created_at
),
-- Match source and destination accounts
legs AS (
    SELECT
        it.id AS transfer_id,
        it.user_id,
        it.amount,
        it.created_at,
        -- Sender Account
        (it.user_id * 2) - 1 AS from_acc_id,
        -- Receiver Account
        (((it.user_id + (it.id % 500) + 1) % 1000) * 2) + 1 AS to_acc_id
    FROM inserted_transfers it
)
-- Insert 100,000 DEBIT entries
INSERT INTO ledger_entries (transfer_id, account_id, entry_type, amount, currency, balance_after, created_at)
SELECT
    transfer_id,
    from_acc_id,
    'DEBIT',
    amount,
    'INR',
    500000 - amount,
    created_at
FROM legs;

-- Insert 100,000 matching CREDIT entries
INSERT INTO ledger_entries (transfer_id, account_id, entry_type, amount, currency, balance_after, created_at)
SELECT
    l.transfer_id,
    l.to_acc_id,
    'CREDIT',
    l.amount,
    'INR',
    500000 + l.amount,
    l.created_at
FROM (
    SELECT
        it.id AS transfer_id,
        it.amount,
        it.created_at,
        (((it.user_id + (it.id % 500) + 1) % 1000) * 2) + 1 AS to_acc_id
    FROM transfers it
    WHERE it.idempotency_key LIKE 'SEED-KEY-%'
) l;

\echo 'Synchronizing stored account balances with ledger entries...'
UPDATE accounts a
SET balance = balance + COALESCE(delta.net_amount, 0),
    updated_at = NOW()
FROM (
    SELECT account_id, SUM(CASE WHEN entry_type = 'CREDIT' THEN amount ELSE -amount END) AS net_amount
    FROM ledger_entries
    WHERE transfer_id IN (SELECT id FROM transfers WHERE idempotency_key LIKE 'SEED-KEY-%')
    GROUP BY account_id
) delta
WHERE a.id = delta.account_id;

-- Re-enable the immutability trigger
ALTER TABLE ledger_entries ENABLE TRIGGER trg_prevent_ledger_update;

COMMIT;

\echo 'Seed completed successfully! 1,000 users, 2,000 accounts, and 100,000 transfers created.'
