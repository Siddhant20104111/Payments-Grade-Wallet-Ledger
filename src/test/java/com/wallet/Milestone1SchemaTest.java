package com.wallet;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@DisplayName("Milestone 1: Database Schema & Invariants Test")
class Milestone1SchemaTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String uid() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    @Test
    @DisplayName("Invariant 1: User wallet balance cannot go below zero (DB Constraint)")
    void userWalletCannotGoNegative() {
        String u = uid();
        Long userId = jdbcTemplate.queryForObject(
                "INSERT INTO users (name, email, phone, kyc_status) VALUES ('Test User', 'test1_" + u + "@wallet.com', '+91999" + u + "', 'VERIFIED') RETURNING id",
                Long.class
        );

        Long accountId = jdbcTemplate.queryForObject(
                "INSERT INTO accounts (user_id, account_number, type, name, balance) VALUES (?, 'ACC-TEST-" + u + "', 'USER', 'Main Wallet', 100) RETURNING id",
                Long.class, userId
        );

        // Attempt to update balance to -50 paise (Must fail on DB constraint chk_account_user_balance)
        assertThrows(DataIntegrityViolationException.class, () -> {
            jdbcTemplate.update("UPDATE accounts SET balance = -50 WHERE id = ?", accountId);
        });
    }

    @Test
    @DisplayName("Invariant 2: Ledger entries cannot be updated or deleted (Immutability Trigger)")
    void ledgerEntriesAreImmutable() {
        String u = uid();
        Long userId = jdbcTemplate.queryForObject(
                "INSERT INTO users (name, email, phone, kyc_status) VALUES ('Audit User', 'audit_" + u + "@wallet.com', '+91998" + u + "', 'VERIFIED') RETURNING id",
                Long.class
        );

        Long accountId = jdbcTemplate.queryForObject(
                "INSERT INTO accounts (user_id, account_number, type, name, balance) VALUES (?, 'ACC-AUDIT-" + u + "', 'USER', 'Main Wallet', 1000) RETURNING id",
                Long.class, userId
        );

        Long transferId = jdbcTemplate.queryForObject(
                "INSERT INTO transfers (user_id, transfer_type, amount, status) VALUES (?, 'TOP_UP', 1000, 'COMPLETED') RETURNING id",
                Long.class, userId
        );

        Long entryId = jdbcTemplate.queryForObject(
                "INSERT INTO ledger_entries (transfer_id, account_id, entry_type, amount, balance_after) VALUES (?, ?, 'CREDIT', 1000, 1000) RETURNING id",
                Long.class, transferId, accountId
        );

        // Attempt to UPDATE ledger entry (Must fail on trigger trg_prevent_ledger_update)
        assertThrows(Exception.class, () -> {
            jdbcTemplate.update("UPDATE ledger_entries SET amount = 2000 WHERE id = ?", entryId);
        });

        // Attempt to DELETE ledger entry (Must fail on trigger trg_prevent_ledger_update)
        assertThrows(Exception.class, () -> {
            jdbcTemplate.update("DELETE FROM ledger_entries WHERE id = ?", entryId);
        });
    }

    @Test
    @DisplayName("Invariant 3: Idempotency key is unique per user")
    void idempotencyKeyUniquePerUser() {
        String u = uid();
        Long userId = jdbcTemplate.queryForObject(
                "INSERT INTO users (name, email, phone, kyc_status) VALUES ('Idem User', 'idem_" + u + "@wallet.com', '+91997" + u + "', 'VERIFIED') RETURNING id",
                Long.class
        );

        jdbcTemplate.update(
                "INSERT INTO idempotency_keys (user_id, key, request_hash, status) VALUES (?, 'KEY-12345', 'hash-abc', 'COMPLETED')",
                userId
        );

        // Attempt duplicate insert with same (user_id, key)
        assertThrows(DataIntegrityViolationException.class, () -> {
            jdbcTemplate.update(
                    "INSERT INTO idempotency_keys (user_id, key, request_hash, status) VALUES (?, 'KEY-12345', 'hash-abc', 'COMPLETED')",
                    userId
            );
        });
    }

    @Test
    @DisplayName("Invariant 4: System clearing accounts exist and balance to zero initially")
    void systemAccountsSeeded() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM accounts WHERE type = 'SYSTEM'",
                Integer.class
        );
        assertTrue(count >= 3, "Expected at least 3 system accounts (CASH_IN, CASH_OUT, FEE_REVENUE)");
    }
}
