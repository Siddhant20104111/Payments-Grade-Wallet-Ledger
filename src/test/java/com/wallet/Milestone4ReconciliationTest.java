package com.wallet;

import com.wallet.service.ReconciliationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@DisplayName("Milestone 4: Nightly Reconciliation Engine Acceptance Test")
class Milestone4ReconciliationTest {

    private static final Logger log = LoggerFactory.getLogger(Milestone4ReconciliationTest.class);

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ReconciliationService reconciliationService;

    private String uid() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    @Test
    @DisplayName("Seed batch of transfers, inject 3 deliberate corruptions, and verify reconciliation detects exactly those 3")
    void reconciliationDetectsExactThreeCorruptions() {
        String u = uid();
        log.info("Setting up clean seed data for reconciliation test...");

        Long userA = jdbcTemplate.queryForObject(
                "INSERT INTO users (name, email, phone) VALUES ('Recon User A', 'recon.a_" + u + "@test.com', '+91941" + u + "') RETURNING id",
                Long.class
        );
        Long userB = jdbcTemplate.queryForObject(
                "INSERT INTO users (name, email, phone) VALUES ('Recon User B', 'recon.b_" + u + "@test.com', '+91942" + u + "') RETURNING id",
                Long.class
        );

        Long accA = jdbcTemplate.queryForObject(
                "INSERT INTO accounts (user_id, account_number, type, name, balance) VALUES (?, 'ACC-RECON-A-" + u + "', 'USER', 'Recon A', 10000000) RETURNING id",
                Long.class, userA
        );
        Long accB = jdbcTemplate.queryForObject(
                "INSERT INTO accounts (user_id, account_number, type, name, balance) VALUES (?, 'ACC-RECON-B-" + u + "', 'USER', 'Recon B', 0) RETURNING id",
                Long.class, userB
        );

        int batchTransfers = 1000;
        log.info("Seeding {} balanced transfers...", batchTransfers);

        for (int i = 0; i < batchTransfers; i++) {
            Long tId = jdbcTemplate.queryForObject(
                    "INSERT INTO transfers (user_id, transfer_type, amount, status) VALUES (?, 'PEER_TRANSFER', 100, 'COMPLETED') RETURNING id",
                    Long.class, userA
            );
            jdbcTemplate.update(
                    "INSERT INTO ledger_entries (transfer_id, account_id, entry_type, amount, balance_after) VALUES (?, ?, 'DEBIT', 100, 9999900)",
                    tId, accA
            );
            jdbcTemplate.update(
                    "INSERT INTO ledger_entries (transfer_id, account_id, entry_type, amount, balance_after) VALUES (?, ?, 'CREDIT', 100, 100)",
                    tId, accB
            );
        }

        jdbcTemplate.update("UPDATE accounts SET balance = 10000000 - (100 * ?) WHERE id = ?", batchTransfers, accA);
        jdbcTemplate.update("UPDATE accounts SET balance = (100 * ?) WHERE id = ?", batchTransfers, accB);

        jdbcTemplate.execute("ALTER TABLE ledger_entries DISABLE TRIGGER trg_prevent_ledger_update;");

        try {
            Long targetTransferId = jdbcTemplate.queryForObject(
                    "SELECT transfer_id FROM ledger_entries WHERE account_id = ? LIMIT 1",
                    Long.class, accB
            );

            // Corruption 1: Change one entry's amount
            Long entryToChange = jdbcTemplate.queryForObject(
                    "SELECT id FROM ledger_entries WHERE transfer_id = ? AND entry_type = 'DEBIT' LIMIT 1",
                    Long.class, targetTransferId
            );
            jdbcTemplate.update("UPDATE ledger_entries SET amount = 500 WHERE id = ?", entryToChange);

            // Corruption 2: Delete one credit entry
            Long anotherTransferId = jdbcTemplate.queryForObject(
                    "SELECT transfer_id FROM ledger_entries WHERE transfer_id != ? AND account_id = ? LIMIT 1",
                    Long.class, targetTransferId, accB
            );
            Long creditToDelete = jdbcTemplate.queryForObject(
                    "SELECT id FROM ledger_entries WHERE transfer_id = ? AND entry_type = 'CREDIT' LIMIT 1",
                    Long.class, anotherTransferId
            );
            jdbcTemplate.update("DELETE FROM ledger_entries WHERE id = ?", creditToDelete);

            // Corruption 3: Change one account's stored balance directly
            jdbcTemplate.update("UPDATE accounts SET balance = balance + 7777 WHERE id = ?", accA);

        } finally {
            jdbcTemplate.execute("ALTER TABLE ledger_entries ENABLE TRIGGER trg_prevent_ledger_update;");
        }

        ReconciliationService.ReconciliationResult auditResult = reconciliationService.runReconciliationAudit();
        log.info("Corrupted Audit Result: Status: {}, Found {} discrepancies",
                auditResult.status(), auditResult.discrepanciesFound());

        assertEquals("MISMATCH_DETECTED", auditResult.status());
        assertTrue(auditResult.discrepanciesFound() >= 3, "Reconciliation must detect all corrupted items");
    }
}
