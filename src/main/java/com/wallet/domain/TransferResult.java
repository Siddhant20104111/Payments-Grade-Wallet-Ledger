package com.wallet.domain;

public record TransferResult(
        Long transferId,
        String status,
        Long fromAccountId,
        Long toAccountId,
        Long amountPaise,
        Long fromBalanceAfter,
        Long toBalanceAfter,
        String message
) {}
