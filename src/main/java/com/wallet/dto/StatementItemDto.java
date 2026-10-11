package com.wallet.dto;

import java.time.OffsetDateTime;

public record StatementItemDto(
        Long entryId,
        Long transferId,
        String entryType, // DEBIT, CREDIT
        Long amountPaise,
        Long balanceAfterPaise,
        String transferType,
        String description,
        OffsetDateTime createdAt
) {}
