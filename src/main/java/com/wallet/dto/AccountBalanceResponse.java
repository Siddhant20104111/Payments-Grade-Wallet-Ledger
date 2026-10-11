package com.wallet.dto;

public record AccountBalanceResponse(
        Long accountId,
        String accountNumber,
        String accountType,
        String currency,
        Long balancePaise,
        Double balanceRupees
) {}
