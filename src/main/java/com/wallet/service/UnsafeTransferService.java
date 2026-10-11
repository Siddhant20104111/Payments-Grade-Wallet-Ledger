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

/**
 * DEMONSTRATION ONLY: Unsafe Transfer Service without row-level locks.
 * Used to reproduce race conditions, lost updates, and overdrafts under concurrency.
 */
@Service
public class UnsafeTransferService {

    private static final Logger log = LoggerFactory.getLogger(UnsafeTransferService.class);
    private final JdbcTemplate jdbcTemplate;

    public UnsafeTransferService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public TransferResult executeUnsafeTransfer(TransferRequest request) {
        // 1. Read balances without any locks
        Long fromBalance;
        try {
            fromBalance = jdbcTemplate.queryForObject(
                    "SELECT balance FROM accounts WHERE id = ?",
                    Long.class, request.fromAccountId()
            );
        } catch (EmptyResultDataAccessException e) {
            throw new AccountNotFoundException("Source account not found: " + request.fromAccountId());
        }

        Long toBalance;
        try {
            toBalance = jdbcTemplate.queryForObject(
                    "SELECT balance FROM accounts WHERE id = ?",
                    Long.class, request.toAccountId()
            );
        } catch (EmptyResultDataAccessException e) {
            throw new AccountNotFoundException("Destination account not found: " + request.toAccountId());
        }

        // 2. Naive check in application code
        if (fromBalance < request.amountPaise()) {
            throw new InsufficientFundsException("Insufficient balance in account: " + request.fromAccountId());
        }

        // Simulate real-world network/IO delay of 5ms to widen the race condition window
        try {
            Thread.sleep(5);
        } catch (InterruptedException ignored) {}

        // 3. Unsafe updates (Lost updates and race conditions happen here!)
        long newFromBalance = fromBalance - request.amountPaise();
        long newToBalance = toBalance + request.amountPaise();

        jdbcTemplate.update("UPDATE accounts SET balance = ? WHERE id = ?", newFromBalance, request.fromAccountId());
        jdbcTemplate.update("UPDATE accounts SET balance = ? WHERE id = ?", newToBalance, request.toAccountId());

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

        // Record ledger entries
        jdbcTemplate.update(
                "INSERT INTO ledger_entries (transfer_id, account_id, entry_type, amount, balance_after) VALUES (?, ?, 'DEBIT', ?, ?)",
                transferId, request.fromAccountId(), request.amountPaise(), newFromBalance
        );
        jdbcTemplate.update(
                "INSERT INTO ledger_entries (transfer_id, account_id, entry_type, amount, balance_after) VALUES (?, ?, 'CREDIT', ?, ?)",
                transferId, request.toAccountId(), request.amountPaise(), newToBalance
        );

        return new TransferResult(
                transferId,
                "COMPLETED",
                request.fromAccountId(),
                request.toAccountId(),
                request.amountPaise(),
                newFromBalance,
                newToBalance,
                "Unsafe transfer succeeded"
        );
    }
}
