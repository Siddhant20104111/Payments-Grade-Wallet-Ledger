package com.wallet.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wallet.domain.*;
import com.wallet.dto.AccountBalanceResponse;
import com.wallet.dto.CreateTransferRequest;
import com.wallet.dto.StatementItemDto;
import com.wallet.service.IdempotencyService;
import com.wallet.service.SafeTransferService;
import jakarta.validation.Valid;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping
public class TransferController {

    private final SafeTransferService safeTransferService;
    private final IdempotencyService idempotencyService;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public TransferController(
            SafeTransferService safeTransferService,
            IdempotencyService idempotencyService,
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper
    ) {
        this.safeTransferService = safeTransferService;
        this.idempotencyService = idempotencyService;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @PostMapping(value = "/transfers", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> createTransfer(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateTransferRequest request
    ) throws JsonProcessingException {

        // 1. Enforce mandatory Idempotency-Key header on all POST payments
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new MissingIdempotencyKeyException("The 'Idempotency-Key' header is mandatory on all payment requests.");
        }

        // Canonical JSON representation of request body for hashing
        String payloadJson = objectMapper.writeValueAsString(request);

        // 2. Atomic Idempotency Check
        Optional<IdempotencyService.IdempotencyRecord> existing =
                idempotencyService.startOrResolve(request.userId(), idempotencyKey, payloadJson);

        // If this request is an identical retry, replay the exact previous response!
        if (existing.isPresent()) {
            IdempotencyService.IdempotencyRecord record = existing.get();
            return ResponseEntity
                    .status(record.responseCode() != null ? record.responseCode() : HttpStatus.OK.value())
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-Cache-Lookup", "HIT-IDEMPOTENT")
                    .body(record.responseBody());
        }

        // 3. New request: Execute atomic transfer
        try {
            TransferResult result = safeTransferService.executeTransfer(new TransferRequest(
                    request.fromAccountId(),
                    request.toAccountId(),
                    request.amountPaise(),
                    request.transferType() != null ? request.transferType() : "PEER_TRANSFER",
                    idempotencyKey,
                    request.description(),
                    request.userId()
            ));

            String responseJson = objectMapper.writeValueAsString(result);

            // 4. Save response on key row for future retries
            idempotencyService.markCompleted(request.userId(), idempotencyKey, HttpStatus.OK.value(), responseJson);

            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-Cache-Lookup", "MISS")
                    .body(responseJson);

        } catch (Exception e) {
            // If transfer failed due to business logic, record failure in idempotency store
            String errorJson = objectMapper.writeValueAsString(
                    ApiError.of("TRANSFER_FAILED", e.getMessage())
            );
            idempotencyService.markFailed(request.userId(), idempotencyKey, HttpStatus.UNPROCESSABLE_ENTITY.value(), errorJson);
            throw e;
        }
    }

    @GetMapping("/accounts/{id}/balance")
    public ResponseEntity<AccountBalanceResponse> getBalance(@PathVariable("id") Long accountId) {
        try {
            AccountBalanceResponse response = jdbcTemplate.queryForObject(
                    "SELECT id, account_number, type, currency, balance FROM accounts WHERE id = ?",
                    (rs, rowNum) -> new AccountBalanceResponse(
                            rs.getLong("id"),
                            rs.getString("account_number"),
                            rs.getString("type"),
                            rs.getString("currency"),
                            rs.getLong("balance"),
                            rs.getLong("balance") / 100.0
                    ),
                    accountId
            );
            return ResponseEntity.ok(response);
        } catch (EmptyResultDataAccessException e) {
            throw new AccountNotFoundException("Account not found: " + accountId);
        }
    }

    @GetMapping("/accounts/{id}/statement")
    public ResponseEntity<List<StatementItemDto>> getStatement(
            @PathVariable("id") Long accountId,
            @RequestParam(value = "limit", defaultValue = "20") int limit,
            @RequestParam(value = "offset", defaultValue = "0") int offset
    ) {
        // Enforce valid pagination ranges
        int boundedLimit = Math.max(1, Math.min(limit, 100));
        int boundedOffset = Math.max(0, offset);

        List<StatementItemDto> entries = jdbcTemplate.query(
                "SELECT le.id, le.transfer_id, le.entry_type, le.amount, le.balance_after, " +
                        "t.transfer_type, t.description, le.created_at " +
                        "FROM ledger_entries le " +
                        "JOIN transfers t ON le.transfer_id = t.id " +
                        "WHERE le.account_id = ? " +
                        "ORDER BY le.created_at DESC, le.id DESC " +
                        "LIMIT ? OFFSET ?",
                (rs, rowNum) -> new StatementItemDto(
                        rs.getLong("id"),
                        rs.getLong("transfer_id"),
                        rs.getString("entry_type"),
                        rs.getLong("amount"),
                        rs.getLong("balance_after"),
                        rs.getString("transfer_type"),
                        rs.getString("description"),
                        rs.getObject("created_at", OffsetDateTime.class)
                ),
                accountId, boundedLimit, boundedOffset
        );

        return ResponseEntity.ok(entries);
    }
}
