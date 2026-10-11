package com.wallet.controller;

import com.wallet.domain.TransferResult;
import com.wallet.service.SafeTransferService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/admin/transfers")
public class AdminTransferController {

    private final SafeTransferService safeTransferService;

    public AdminTransferController(SafeTransferService safeTransferService) {
        this.safeTransferService = safeTransferService;
    }

    @PostMapping("/{id}/release")
    public ResponseEntity<TransferResult> releaseTransfer(
            @PathVariable("id") Long transferId,
            @RequestBody Map<String, Object> body
    ) {
        Long fromAccountId = ((Number) body.get("fromAccountId")).longValue();
        Long toAccountId = ((Number) body.get("toAccountId")).longValue();
        String adminUser = (String) body.getOrDefault("adminUser", "admin");
        String reason = (String) body.getOrDefault("reason", "Approved after KYC review");

        TransferResult result = safeTransferService.releaseHeldTransfer(transferId, fromAccountId, toAccountId, adminUser, reason);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/{id}/reject")
    public ResponseEntity<Map<String, String>> rejectTransfer(
            @PathVariable("id") Long transferId,
            @RequestBody(required = false) Map<String, String> body
    ) {
        String adminUser = body != null ? body.getOrDefault("adminUser", "admin") : "admin";
        String reason = body != null ? body.getOrDefault("reason", "Rejected due to suspicious activity") : "Suspicious activity";

        safeTransferService.rejectHeldTransfer(transferId, adminUser, reason);
        return ResponseEntity.ok(Map.of("status", "REJECTED", "transferId", transferId.toString()));
    }
}
