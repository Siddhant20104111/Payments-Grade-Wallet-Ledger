package com.wallet.domain;

public record TransferRequest(
        Long fromAccountId,
        Long toAccountId,
        Long amountPaise,
        String transferType, // PEER_TRANSFER, TOP_UP, WITHDRAWAL, etc.
        String idempotencyKey,
        String description,
        Long userId
) {
    public TransferRequest {
        if (amountPaise == null || amountPaise <= 0) {
            throw new InvalidTransferException("Transfer amount must be strictly positive (in paise).");
        }
        if (fromAccountId == null || toAccountId == null) {
            throw new InvalidTransferException("Source and destination accounts are required.");
        }
        if (fromAccountId.equals(toAccountId)) {
            throw new InvalidTransferException("Source and destination account cannot be identical.");
        }
    }
}
