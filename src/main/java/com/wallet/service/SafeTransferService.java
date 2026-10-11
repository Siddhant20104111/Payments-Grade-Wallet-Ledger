package com.wallet.service;

import com.wallet.domain.AccountNotFoundException;
import com.wallet.domain.InsufficientFundsException;
import com.wallet.domain.TransferRequest;
import com.wallet.domain.TransferResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Production-grade Safe Transfer Service using:
 * 1. Read Committed isolation level with pessimistic row locks (SELECT ... FOR UPDATE)
 * 2. Consistent lock ordering (account_id ASC) to eliminate deadlocks
 * 3. Fraud rule evaluation before money moves (held transfers)
 * 4. Double-entry ledger invariant enforcement
 */
@Service
public class SafeTransferService {

    private static final Logger log = LoggerFactory.getLogger(SafeTransferService.class);

    private final JdbcTemplate jdbcTemplate;
    private final FraudDetectionService fraudDetectionService;

    public SafeTransferService(JdbcTemplate jdbcTemplate, FraudDetectionService fraudDetectionService) {
        this.jdbcTemplate = jdbcTemplate;
        this.fraudDetectionService = fraudDetectionService;
    }

    private record AccountSnapshot(Long id, String type, Long balance, Long userId) {}

    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public TransferResult executeTransfer(TransferRequest request) {
        // 1. Determine deadlock-free lock acquisition order (min ID first, then max ID)
        long firstLockId = Math.min(request.fromAccountId(), request.toAccountId());
        long secondLockId = Math.max(request.fromAccountId(), request.toAccountId());

        // 2. Acquire exclusive row-level locks in strictly ascending order
        AccountSnapshot firstLocked = lockAccount(firstLockId);
        AccountSnapshot secondLocked = lockAccount(secondLockId);

        AccountSnapshot fromAccount = (request.fromAccountId().equals(firstLockId)) ? firstLocked : secondLocked;
        AccountSnapshot toAccount = (request.toAccountId().equals(firstLockId)) ? firstLocked : secondLocked;

        // 3. Re-validate balance inside the lock
        if ("USER".equals(fromAccount.type()) && fromAccount.balance() < request.amountPaise()) {
            throw new InsufficientFundsException(
                    String.format("Insufficient funds in account %d. Current balance: %d paise, Required: %d paise",
                            fromAccount.id(), fromAccount.balance(), request.amountPaise())
            );
        }

        // 4. Evaluate Fraud Detection Rules
        FraudDetectionService.FraudCheckResult fraudResult =
                fraudDetectionService.evaluateTransfer(request.userId(), request.amountPaise(), request.description());

        if (fraudResult.isFlagged()) {
                Long transferId = jdbcTemplate.queryForObject(
                        "INSERT INTO transfers (user_id, idempotency_key, transfer_type, amount, status, description) " +
                                "VALUES (?, ?, ?, ?, 'HELD', ?) RETURNING id",
                        Long.class,
                        request.userId(),
                        request.idempotencyKey(),
                        request.transferType() != null ? request.transferType() : "PEER_TRANSFER",
                        request.amountPaise(),
                        request.description()
                );

                jdbcTemplate.update(
                        "INSERT INTO risk_flags (transfer_id, rule_code, ai_evaluation, ai_explanation) " +
                                "VALUES (?, ?, 'review', ?)",
                        transferId, fraudResult.ruleCode(), fraudResult.reason()
                );

                jdbcTemplate.update(
                        "INSERT INTO audit_logs (transfer_id, action, performed_by, reason) VALUES (?, 'HELD', 'SYSTEM_FRAUD_ENGINE', ?)",
                        transferId, fraudResult.reason()
                );

                log.warn("Transfer {} flagged and held by fraud rule: {}", transferId, fraudResult.ruleCode());

                return new TransferResult(
                        transferId,
                        "HELD",
                        fromAccount.id(),
                        toAccount.id(),
                        request.amountPaise(),
                        fromAccount.balance(),
                        toAccount.balance(),
                        "Transfer flagged by security rules and held for administrative review: " + fraudResult.reason()
                );
            }

        // 5. Transfer is Clean: Move money and write double-entry ledger lines
        long newFromBalance = fromAccount.balance() - request.amountPaise();
        long newToBalance = toAccount.balance() + request.amountPaise();

        jdbcTemplate.update(
                "UPDATE accounts SET balance = ?, updated_at = NOW() WHERE id = ?",
                newFromBalance, fromAccount.id()
        );
        jdbcTemplate.update(
                "UPDATE accounts SET balance = ?, updated_at = NOW() WHERE id = ?",
                newToBalance, toAccount.id()
        );

        Long transferId = jdbcTemplate.queryForObject(
                "INSERT INTO transfers (user_id, idempotency_key, transfer_type, amount, status, description) " +
                        "VALUES (?, ?, ?, ?, 'COMPLETED', ?) RETURNING id",
                Long.class,
                request.userId(),
                request.idempotencyKey(),
                request.transferType() != null ? request.transferType() : "PEER_TRANSFER",
                request.amountPaise(),
                request.description()
        );

        // Write ledger entries
        jdbcTemplate.update(
                "INSERT INTO ledger_entries (transfer_id, account_id, entry_type, amount, balance_after) " +
                        "VALUES (?, ?, 'DEBIT', ?, ?)",
                transferId, fromAccount.id(), request.amountPaise(), newFromBalance
        );
        jdbcTemplate.update(
                "INSERT INTO ledger_entries (transfer_id, account_id, entry_type, amount, balance_after) " +
                        "VALUES (?, ?, 'CREDIT', ?, ?)",
                transferId, toAccount.id(), request.amountPaise(), newToBalance
        );

        return new TransferResult(
                transferId,
                "COMPLETED",
                fromAccount.id(),
                toAccount.id(),
                request.amountPaise(),
                newFromBalance,
                newToBalance,
                "Transfer completed successfully"
        );
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public TransferResult releaseHeldTransfer(Long transferId, Long fromAccountId, Long toAccountId, String adminUser, String reason) {
        Long firstLockId = Math.min(fromAccountId, toAccountId);
        Long secondLockId = Math.max(fromAccountId, toAccountId);

        AccountSnapshot firstLocked = lockAccount(firstLockId);
        AccountSnapshot secondLocked = lockAccount(secondLockId);

        AccountSnapshot fromAccount = (fromAccountId.equals(firstLockId)) ? firstLocked : secondLocked;
        AccountSnapshot toAccount = (toAccountId.equals(firstLockId)) ? firstLocked : secondLocked;

        Long amount = jdbcTemplate.queryForObject(
                "SELECT amount FROM transfers WHERE id = ? AND status = 'HELD' FOR UPDATE",
                Long.class, transferId
        );

        if (fromAccount.balance() < amount) {
            throw new InsufficientFundsException("Cannot release held transfer: source account has insufficient funds.");
        }

        long newFromBalance = fromAccount.balance() - amount;
        long newToBalance = toAccount.balance() + amount;

        jdbcTemplate.update("UPDATE accounts SET balance = ?, updated_at = NOW() WHERE id = ?", newFromBalance, fromAccount.id());
        jdbcTemplate.update("UPDATE accounts SET balance = ?, updated_at = NOW() WHERE id = ?", newToBalance, toAccount.id());

        jdbcTemplate.update("UPDATE transfers SET status = 'COMPLETED', updated_at = NOW() WHERE id = ?", transferId);

        jdbcTemplate.update(
                "INSERT INTO ledger_entries (transfer_id, account_id, entry_type, amount, balance_after) VALUES (?, ?, 'DEBIT', ?, ?)",
                transferId, fromAccount.id(), amount, newFromBalance
        );
        jdbcTemplate.update(
                "INSERT INTO ledger_entries (transfer_id, account_id, entry_type, amount, balance_after) VALUES (?, ?, 'CREDIT', ?, ?)",
                transferId, toAccount.id(), amount, newToBalance
        );

        jdbcTemplate.update(
                "INSERT INTO audit_logs (transfer_id, action, performed_by, reason) VALUES (?, 'RELEASED', ?, ?)",
                transferId, adminUser, reason
        );

        return new TransferResult(transferId, "COMPLETED", fromAccountId, toAccountId, amount, newFromBalance, newToBalance, "Held transfer released by admin");
    }

    @Transactional
    public void rejectHeldTransfer(Long transferId, String adminUser, String reason) {
        jdbcTemplate.update("UPDATE transfers SET status = 'REJECTED', updated_at = NOW() WHERE id = ? AND status = 'HELD'", transferId);
        jdbcTemplate.update("INSERT INTO audit_logs (transfer_id, action, performed_by, reason) VALUES (?, 'REJECTED', ?, ?)", transferId, adminUser, reason);
    }

    private AccountSnapshot lockAccount(Long accountId) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT id, type, balance, user_id FROM accounts WHERE id = ? FOR UPDATE",
                    (rs, rowNum) -> new AccountSnapshot(
                            rs.getLong("id"),
                            rs.getString("type"),
                            rs.getLong("balance"),
                            rs.getObject("user_id", Long.class)
                    ),
                    accountId
            );
        } catch (EmptyResultDataAccessException e) {
            throw new AccountNotFoundException("Account not found: " + accountId);
        }
    }
}
