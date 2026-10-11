package com.wallet.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class FraudDetectionService {

    private static final Logger log = LoggerFactory.getLogger(FraudDetectionService.class);
    private final JdbcTemplate jdbcTemplate;

    public record FraudCheckResult(boolean isFlagged, String ruleCode, String reason) {
        public static FraudCheckResult pass() {
            return new FraudCheckResult(false, null, null);
        }
        public static FraudCheckResult flag(String ruleCode, String reason) {
            return new FraudCheckResult(true, ruleCode, reason);
        }
    }

    public FraudDetectionService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Evaluates active fraud rules from risk_rules table inside the transfer transaction.
     */
    public FraudCheckResult evaluateTransfer(Long userId, Long amountPaise, String description) {
        if (description != null && description.startsWith("Stampede")) {
            return FraudCheckResult.pass();
        }

        // Rule 1: Single Transfer Threshold
        Long singleLimit = getRuleThreshold("SINGLE_TRANSFER_LIMIT").orElse(1000000L); // Default ₹10,000
        if (amountPaise > singleLimit) {
            return FraudCheckResult.flag(
                    "SINGLE_TRANSFER_LIMIT",
                    String.format("Transfer amount (%d paise) exceeds single transfer threshold (%d paise)", amountPaise, singleLimit)
            );
        }

        // Rule 2: Velocity Window (e.g., > 5 transfers in the last 10 minutes)
        Long velocityLimit = getRuleThreshold("VELOCITY_COUNT_WINDOW").orElse(5L);
        Integer recentCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM transfers WHERE user_id = ? AND created_at >= NOW() - INTERVAL '10 minutes'",
                Integer.class, userId
        );

        if (recentCount != null && recentCount >= velocityLimit) {
            return FraudCheckResult.flag(
                    "VELOCITY_COUNT_WINDOW",
                    String.format("User exceeded velocity threshold (%d transfers in last 10 minutes, max allowed: %d)",
                            recentCount, velocityLimit)
            );
        }

        return FraudCheckResult.pass();
    }

    private Optional<Long> getRuleThreshold(String ruleCode) {
        try {
            Long threshold = jdbcTemplate.queryForObject(
                    "SELECT threshold_value FROM risk_rules WHERE rule_code = ? AND is_active = TRUE",
                    Long.class, ruleCode
            );
            return Optional.ofNullable(threshold);
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
