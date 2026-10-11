package com.wallet.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;

@Service
public class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

    private final JdbcTemplate jdbcTemplate;
    private final PlatformTransactionManager transactionManager;

    public record ReconciliationResult(
            Long runId,
            String status,
            long totalTransfersChecked,
            int discrepanciesFound,
            List<String> issues
    ) {}

    public ReconciliationService(JdbcTemplate jdbcTemplate, PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionManager = transactionManager;
    }

    /**
     * Nightly scheduled execution (runs at 02:00 AM every day)
     */
    @Scheduled(cron = "0 0 2 * * ?")
    public void runNightlyReconciliationSchedule() {
        log.info("Triggering scheduled nightly reconciliation audit...");
        runReconciliationAudit();
    }

    /**
     * Executes the reconciliation audit inside a REPEATABLE READ snapshot.
     */
    public ReconciliationResult runReconciliationAudit() {
        // 1. Transaction Template configured strictly with REPEATABLE READ isolation
        DefaultTransactionDefinition def = new DefaultTransactionDefinition();
        def.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        def.setName("ReconciliationAuditTx");
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager, def);

        return txTemplate.execute(status -> {
            // 2. Acquire Job Lock using NOWAIT to prevent overlapping executions
            try {
                jdbcTemplate.queryForObject(
                        "SELECT job_name FROM job_locks WHERE job_name = 'NIGHTLY_RECONCILIATION' FOR UPDATE NOWAIT",
                        String.class
                );
            } catch (CannotAcquireLockException e) {
                log.warn("Reconciliation job lock is currently held by another worker. Skipping run.");
                return new ReconciliationResult(null, "SKIPPED_LOCK_HELD", 0, 0, List.of("Lock held by another worker"));
            }

            log.info("Starting reconciliation audit in REPEATABLE READ isolation snapshot...");

            // Create Run Record
            Long runId = jdbcTemplate.queryForObject(
                    "INSERT INTO reconciliation_runs (started_at, status) VALUES (NOW(), 'RUNNING') RETURNING id",
                    Long.class
            );

            List<String> detectedIssues = new ArrayList<>();

            // -----------------------------------------------------------------
            // Check 1: Zero-Sum Ledger (Total Debits must equal Total Credits)
            // -----------------------------------------------------------------
            Long netLedgerSum = jdbcTemplate.queryForObject(
                    "SELECT COALESCE(SUM(CASE WHEN entry_type = 'CREDIT' THEN amount ELSE -amount END), 0) FROM ledger_entries",
                    Long.class
            );
            if (netLedgerSum != null && netLedgerSum != 0) {
                String issue = "Zero-sum invariant broken: Net ledger delta is " + netLedgerSum + " paise (must be 0)";
                recordIssue(runId, "ZERO_SUM_CHECK", null, "0", netLedgerSum.toString());
                detectedIssues.add(issue);
            }

            // -----------------------------------------------------------------
            // Check 2: Every Transfer Balances (Debits == Credits per transfer)
            // -----------------------------------------------------------------
            List<Long> unbalancedTransfers = jdbcTemplate.query(
                    "SELECT transfer_id " +
                            "FROM ledger_entries " +
                            "GROUP BY transfer_id " +
                            "HAVING SUM(CASE WHEN entry_type = 'CREDIT' THEN amount ELSE -amount END) != 0",
                    (rs, rowNum) -> rs.getLong("transfer_id")
            );
            for (Long tId : unbalancedTransfers) {
                String issue = "Transfer " + tId + " has unbalanced debit/credit legs";
                recordIssue(runId, "UNBALANCED_TRANSFER", tId, "0 delta", "unbalanced");
                detectedIssues.add(issue);
            }

            // -----------------------------------------------------------------
            // Check 3: Stored Balances Match Ledger-Derived Balances
            // -----------------------------------------------------------------
            List<AccountMismatch> mismatches = jdbcTemplate.query(
                    "SELECT a.id, a.account_number, a.balance AS stored_balance, " +
                            "COALESCE(SUM(CASE WHEN le.entry_type = 'CREDIT' THEN le.amount ELSE -le.amount END), 0) AS computed_balance " +
                            "FROM accounts a " +
                            "LEFT JOIN ledger_entries le ON a.id = le.account_id " +
                            "GROUP BY a.id, a.account_number, a.balance " +
                            "HAVING a.balance != COALESCE(SUM(CASE WHEN le.entry_type = 'CREDIT' THEN le.amount ELSE -le.amount END), 0)",
                    (rs, rowNum) -> new AccountMismatch(
                            rs.getLong("id"),
                            rs.getString("account_number"),
                            rs.getLong("stored_balance"),
                            rs.getLong("computed_balance")
                    )
            );
            for (AccountMismatch m : mismatches) {
                String issue = String.format("Account %s (id: %d) stored balance %d != computed balance %d",
                        m.accountNumber, m.accountId, m.storedBalance, m.computedBalance);
                recordIssue(runId, "STORED_BALANCE_MISMATCH", m.accountId,
                        String.valueOf(m.computedBalance), String.valueOf(m.storedBalance));
                detectedIssues.add(issue);
            }

            // -----------------------------------------------------------------
            // Check 4: No Overdrafts on User Accounts
            // -----------------------------------------------------------------
            List<Long> overdrawnAccounts = jdbcTemplate.query(
                    "SELECT id FROM accounts WHERE type = 'USER' AND balance < 0",
                    (rs, rowNum) -> rs.getLong("id")
            );
            for (Long aId : overdrawnAccounts) {
                String issue = "User account " + aId + " is overdrawn (< 0)";
                recordIssue(runId, "USER_OVERDRAFT", aId, ">= 0", "negative");
                detectedIssues.add(issue);
            }

            // -----------------------------------------------------------------
            // Check 5: No Orphaned Transfers or Entries
            // -----------------------------------------------------------------
            List<Long> orphanTransfers = jdbcTemplate.query(
                    "SELECT t.id FROM transfers t LEFT JOIN ledger_entries le ON t.id = le.transfer_id " +
                            "WHERE t.status = 'COMPLETED' AND le.id IS NULL",
                    (rs, rowNum) -> rs.getLong("id")
            );
            for (Long oId : orphanTransfers) {
                String issue = "Transfer " + oId + " is COMPLETED but has no ledger entries";
                recordIssue(runId, "ORPHAN_TRANSFER", oId, "has entries", "zero entries");
                detectedIssues.add(issue);
            }

            Long totalTransfersChecked = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM transfers WHERE status = 'COMPLETED'",
                    Long.class
            );

            String finalStatus = detectedIssues.isEmpty() ? "SUCCESS" : "MISMATCH_DETECTED";

            // Update Run Record
            jdbcTemplate.update(
                    "UPDATE reconciliation_runs SET finished_at = NOW(), status = ?, " +
                            "total_transfers_checked = ?, total_discrepancies_found = ? WHERE id = ?",
                    finalStatus, totalTransfersChecked, detectedIssues.size(), runId
            );

            log.info("Reconciliation audit finished. Status: {}, Transfers checked: {}, Discrepancies: {}",
                    finalStatus, totalTransfersChecked, detectedIssues.size());

            return new ReconciliationResult(
                    runId,
                    finalStatus,
                    totalTransfersChecked != null ? totalTransfersChecked : 0,
                    detectedIssues.size(),
                    detectedIssues
            );
        });
    }

    private void recordIssue(Long runId, String checkType, Long entityId, String expected, String actual) {
        jdbcTemplate.update(
                "INSERT INTO reconciliation_issues (run_id, check_type, entity_id, expected_value, actual_value) " +
                        "VALUES (?, ?, ?, ?, ?)",
                runId, checkType, entityId, expected, actual
        );
    }

    private record AccountMismatch(Long accountId, String accountNumber, Long storedBalance, Long computedBalance) {}
}
