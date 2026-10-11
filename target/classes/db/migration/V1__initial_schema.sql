-- =============================================================================
-- Migration V1: Payments-Grade Wallet Ledger Schema
-- PostgreSQL 15+ compatible DDL
-- =============================================================================

-- 1. Users Table
CREATE TABLE IF NOT EXISTS users (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    email VARCHAR(255) NOT NULL UNIQUE,
    phone VARCHAR(20) NOT NULL UNIQUE,
    kyc_status VARCHAR(20) NOT NULL DEFAULT 'PENDING'
        CHECK (kyc_status IN ('PENDING', 'VERIFIED', 'REJECTED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- 2. Accounts Table (Unifies User Wallets & System Clearing Accounts)
CREATE TABLE IF NOT EXISTS accounts (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id BIGINT REFERENCES users(id) ON DELETE RESTRICT,
    account_number VARCHAR(50) NOT NULL UNIQUE,
    type VARCHAR(20) NOT NULL CHECK (type IN ('USER', 'SYSTEM')),
    name VARCHAR(100) NOT NULL,
    currency VARCHAR(3) NOT NULL DEFAULT 'INR' CHECK (currency = 'INR'),
    balance BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_account_user_balance CHECK (type = 'SYSTEM' OR balance >= 0),
    CONSTRAINT chk_account_type_user_ref CHECK (
        (type = 'USER' AND user_id IS NOT NULL) OR
        (type = 'SYSTEM' AND user_id IS NULL)
    )
);

-- 3. Transfers Table (The Business Transaction Event)
CREATE TABLE IF NOT EXISTS transfers (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id BIGINT REFERENCES users(id),
    idempotency_key VARCHAR(255),
    transfer_type VARCHAR(30) NOT NULL CHECK (
        transfer_type IN ('TOP_UP', 'PEER_TRANSFER', 'WITHDRAWAL', 'FEE', 'REVERSAL')
    ),
    amount BIGINT NOT NULL CHECK (amount > 0),
    currency VARCHAR(3) NOT NULL DEFAULT 'INR' CHECK (currency = 'INR'),
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK (
        status IN ('PENDING', 'COMPLETED', 'HELD', 'REJECTED', 'FAILED')
    ),
    description VARCHAR(255),
    parent_transfer_id BIGINT REFERENCES transfers(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- 4. Ledger Entries Table (Double-Entry Financial Records)
CREATE TABLE IF NOT EXISTS ledger_entries (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    transfer_id BIGINT NOT NULL REFERENCES transfers(id) ON DELETE RESTRICT,
    account_id BIGINT NOT NULL REFERENCES accounts(id) ON DELETE RESTRICT,
    entry_type VARCHAR(10) NOT NULL CHECK (entry_type IN ('DEBIT', 'CREDIT')),
    amount BIGINT NOT NULL CHECK (amount > 0),
    currency VARCHAR(3) NOT NULL DEFAULT 'INR' CHECK (currency = 'INR'),
    balance_after BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- 5. Immutability Trigger for Ledger Entries (Prevent UPDATE or DELETE)
CREATE OR REPLACE FUNCTION prevent_ledger_entry_mutation()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'Ledger entries are immutable and cannot be updated or deleted. Transfer ID: %, Entry ID: %',
        OLD.transfer_id, OLD.id;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_prevent_ledger_update ON ledger_entries;
CREATE TRIGGER trg_prevent_ledger_update
    BEFORE UPDATE OR DELETE ON ledger_entries
    FOR EACH ROW
    EXECUTE FUNCTION prevent_ledger_entry_mutation();

-- 6. Idempotency Keys Table
CREATE TABLE IF NOT EXISTS idempotency_keys (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    key VARCHAR(255) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    status VARCHAR(20) NOT NULL CHECK (status IN ('IN_PROGRESS', 'COMPLETED', 'FAILED')),
    response_code INTEGER,
    response_body TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_user_idempotency_key UNIQUE (user_id, key)
);

-- 7. Risk Rules Table (Configurable Fraud Thresholds)
CREATE TABLE IF NOT EXISTS risk_rules (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    rule_code VARCHAR(50) NOT NULL UNIQUE,
    description VARCHAR(255) NOT NULL,
    threshold_value BIGINT NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- 8. Risk Flags Table (Fraud Evaluations & AI Explanations)
CREATE TABLE IF NOT EXISTS risk_flags (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    transfer_id BIGINT NOT NULL REFERENCES transfers(id) ON DELETE CASCADE,
    rule_code VARCHAR(50) NOT NULL,
    ai_evaluation VARCHAR(20) CHECK (ai_evaluation IN ('likely_ok', 'review', 'likely_fraud')),
    ai_explanation TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- 9. Audit Logs Table (Administrative State Changes)
CREATE TABLE IF NOT EXISTS audit_logs (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    transfer_id BIGINT NOT NULL REFERENCES transfers(id) ON DELETE CASCADE,
    action VARCHAR(50) NOT NULL,
    performed_by VARCHAR(100) NOT NULL,
    reason TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- 10. Job Locks Table (Single-Instance Scheduled Job Execution)
CREATE TABLE IF NOT EXISTS job_locks (
    job_name VARCHAR(100) PRIMARY KEY,
    locked_by VARCHAR(100),
    locked_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- 11. Reconciliation Audit Tables
CREATE TABLE IF NOT EXISTS reconciliation_runs (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    started_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    finished_at TIMESTAMPTZ,
    status VARCHAR(30) NOT NULL,
    total_transfers_checked BIGINT NOT NULL DEFAULT 0,
    total_discrepancies_found BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS reconciliation_issues (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    run_id BIGINT NOT NULL REFERENCES reconciliation_runs(id) ON DELETE CASCADE,
    check_type VARCHAR(100) NOT NULL,
    entity_id BIGINT,
    expected_value TEXT,
    actual_value TEXT,
    detected_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- =============================================================================
-- Core Indexes
-- =============================================================================

-- Statement queries: filter by account, order newest first
CREATE INDEX IF NOT EXISTS idx_ledger_entries_account_created
    ON ledger_entries (account_id, created_at DESC, id DESC);

-- Fast lookup of all legs for a transfer
CREATE INDEX IF NOT EXISTS idx_ledger_entries_transfer_id
    ON ledger_entries (transfer_id);

-- Transfer history per user
CREATE INDEX IF NOT EXISTS idx_transfers_user_created
    ON transfers (user_id, created_at DESC);

-- Idempotency key lookup
CREATE INDEX IF NOT EXISTS idx_idempotency_lookup
    ON idempotency_keys (user_id, key);

-- Reconciliation: look up issues per run
CREATE INDEX IF NOT EXISTS idx_reconciliation_issues_run
    ON reconciliation_issues (run_id);

-- =============================================================================
-- System Accounts Initial Seed
-- =============================================================================
INSERT INTO accounts (account_number, type, name, currency, balance)
VALUES 
    ('SYS-CASH-IN-001', 'SYSTEM', 'CASH_IN_CLEARING', 'INR', 0),
    ('SYS-CASH-OUT-001', 'SYSTEM', 'CASH_OUT_CLEARING', 'INR', 0),
    ('SYS-FEE-REV-001', 'SYSTEM', 'FEE_REVENUE', 'INR', 0)
ON CONFLICT (account_number) DO NOTHING;

-- Initial Risk Rules
INSERT INTO risk_rules (rule_code, description, threshold_value, is_active)
VALUES 
    ('SINGLE_TRANSFER_LIMIT', 'Transfers above ₹10,000 are held for review', 1000000, true),
    ('VELOCITY_COUNT_WINDOW', 'More than 5 transfers in 10 minutes are held', 5, true)
ON CONFLICT (rule_code) DO NOTHING;

-- Initial Job Lock Row for Reconciliation
INSERT INTO job_locks (job_name, locked_by, locked_at)
VALUES ('NIGHTLY_RECONCILIATION', 'INIT', NOW())
ON CONFLICT (job_name) DO NOTHING;
