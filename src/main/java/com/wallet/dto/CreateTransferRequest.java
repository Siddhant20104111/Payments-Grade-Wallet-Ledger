package com.wallet.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record CreateTransferRequest(
        @NotNull(message = "fromAccountId is required") Long fromAccountId,
        @NotNull(message = "toAccountId is required") Long toAccountId,
        @NotNull(message = "amountPaise is required") @Positive(message = "amountPaise must be greater than 0") Long amountPaise,
        String transferType,
        String description,
        @NotNull(message = "userId is required") Long userId
) {}
