package com.wallet.service;

import com.wallet.domain.IdempotencyConflictException;
import com.wallet.domain.IdempotencyPayloadMismatchException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

@Service
public class IdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);
    private final JdbcTemplate jdbcTemplate;

    public record IdempotencyRecord(
            String status,
            String requestHash,
            Integer responseCode,
            String responseBody
    ) {}

    public IdempotencyService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Computes deterministic SHA-256 hash of the request payload
     */
    public String computeSha256(String payload) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    }

    /**
     * Atomically registers a key or inspects an existing execution.
     * Returns Optional.empty() if caller must proceed to execute the transfer.
     * Returns Optional.of(CachedRecord) if key was already completed and response should be replayed.
     */
    public Optional<IdempotencyRecord> startOrResolve(Long userId, String key, String requestPayload) {
        String currentHash = computeSha256(requestPayload != null ? requestPayload : "");

        try {
            // Attempt atomic insert with IN_PROGRESS status
            jdbcTemplate.update(
                    "INSERT INTO idempotency_keys (user_id, key, request_hash, status) VALUES (?, ?, ?, 'IN_PROGRESS')",
                    userId, key, currentHash
            );
            // Insert succeeded -> We are the first/winning thread! Proceed to execute.
            return Optional.empty();
        } catch (DuplicateKeyException e) {
            // Key already exists. Check the existing record.
            IdempotencyRecord existing = getExistingRecord(userId, key);

            // Case 1: Payload mismatch
            if (!existing.requestHash().equals(currentHash)) {
                throw new IdempotencyPayloadMismatchException(
                        "Idempotency key '" + key + "' was previously used with a different request payload."
                );
            }

            // Case 2: Still in flight -> Wait up to 1 second for resolution before rejecting with 409
            if ("IN_PROGRESS".equals(existing.status())) {
                for (int i = 0; i < 10; i++) {
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException ignored) {}
                    existing = getExistingRecord(userId, key);
                    if (!"IN_PROGRESS".equals(existing.status())) {
                        break;
                    }
                }
                if ("IN_PROGRESS".equals(existing.status())) {
                    throw new IdempotencyConflictException(
                            "A request with idempotency key '" + key + "' is currently being processed."
                    );
                }
            }

            // Case 3: Completed -> Return cached response
            return Optional.of(existing);
        }
    }

    public void markCompleted(Long userId, String key, int statusCode, String responseBody) {
        jdbcTemplate.update(
                "UPDATE idempotency_keys SET status = 'COMPLETED', response_code = ?, response_body = ?, updated_at = NOW() " +
                        "WHERE user_id = ? AND key = ?",
                statusCode, responseBody, userId, key
        );
    }

    public void markFailed(Long userId, String key, int statusCode, String responseBody) {
        jdbcTemplate.update(
                "UPDATE idempotency_keys SET status = 'FAILED', response_code = ?, response_body = ?, updated_at = NOW() " +
                        "WHERE user_id = ? AND key = ?",
                statusCode, responseBody, userId, key
        );
    }

    private IdempotencyRecord getExistingRecord(Long userId, String key) {
        return jdbcTemplate.queryForObject(
                "SELECT status, request_hash, response_code, response_body FROM idempotency_keys WHERE user_id = ? AND key = ?",
                (rs, rowNum) -> new IdempotencyRecord(
                        rs.getString("status"),
                        rs.getString("request_hash"),
                        rs.getObject("response_code", Integer.class),
                        rs.getString("response_body")
                ),
                userId, key
        );
    }
}
