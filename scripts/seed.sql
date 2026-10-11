-- =============================================================================
-- Ultra-Fast PostgreSQL Seed Script: 1,000 Users, 2,000 Wallets, 100,000 Transfers
-- Executable in under 3 seconds using direct series generation
-- =============================================================================

BEGIN;

ALTER TABLE ledger_entries DISABLE TRIGGER trg_prevent_ledger_update;

\echo '1/4: Seeding 1,000 users...'
INSERT INTO users (name, email, phone, kyc_status, created_at, updated_at)
SELECT
    'User ' || i,
    'user.' || i || '@seed-wallet.com',
    '+917' || lpad(i::text, 9, '0'),
    'VERIFIED',
    NOW(),
    NOW()
FROM generate_series(1, 1000) AS i
ON CONFLICT DO NOTHING;

\echo '2/4: Seeding 2,000 wallets (Main & Savings)...'
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
WHERE u.email LIKE '%@seed-wallet.com'
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
WHERE u.email LIKE '%@seed-wallet.com'
ON CONFLICT (account_number) DO NOTHING;

\echo '3/4: Inserting 100,000 transfer records...'
CREATE TEMP TABLE tmp_bounds AS
SELECT 
    (SELECT MIN(id) FROM users WHERE email LIKE '%@seed-wallet.com') AS min_u,
    (SELECT MIN(id) FROM accounts WHERE account_number LIKE 'W-%-MAIN' AND user_id IN (SELECT id FROM users WHERE email LIKE '%@seed-wallet.com')) AS min_a;

CREATE TEMP TABLE tmp_t_ids (
    transfer_id BIGINT,
    amount BIGINT,
    seq BIGINT GENERATED ALWAYS AS IDENTITY,
    created_at TIMESTAMPTZ
);

WITH new_transfers AS (
    INSERT INTO transfers (user_id, idempotency_key, transfer_type, amount, currency, status, description, created_at, updated_at)
    SELECT
        b.min_u + (s % 1000),
        'SEED-UUID-' || s,
        'PEER_TRANSFER',
        ((s % 500) + 1) * 100,
        'INR',
        'COMPLETED',
        'Bulk transfer #' || s,
        NOW() - ((100000 - s) || ' seconds')::interval,
        NOW()
    FROM generate_series(1, 100000) AS s, tmp_bounds b
    RETURNING id, amount, created_at
)
INSERT INTO tmp_t_ids (transfer_id, amount, created_at)
SELECT id, amount, created_at FROM new_transfers;

\echo '4/4: Writing 200,000 double-entry ledger entries (100k DEBIT + 100k CREDIT)...'
-- Insert DEBIT lines
INSERT INTO ledger_entries (transfer_id, account_id, entry_type, amount, currency, balance_after, created_at)
SELECT
    t.transfer_id,
    b.min_a + (t.seq % 1000),
    'DEBIT',
    t.amount,
    'INR',
    500000 - t.amount,
    t.created_at
FROM tmp_t_ids t, tmp_bounds b;

-- Insert CREDIT lines
INSERT INTO ledger_entries (transfer_id, account_id, entry_type, amount, currency, balance_after, created_at)
SELECT
    t.transfer_id,
    b.min_a + ((t.seq + 1) % 1000),
    'CREDIT',
    t.amount,
    'INR',
    500000 + t.amount,
    t.created_at
FROM tmp_t_ids t, tmp_bounds b;

-- Re-enable immutability trigger
ALTER TABLE ledger_entries ENABLE TRIGGER trg_prevent_ledger_update;

COMMIT;

\echo 'SUCCESS: 1,000 users, 2,000 accounts, and 100,000 balanced transfers seeded!'
