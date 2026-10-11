package com.wallet.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class DefaultLlmService implements LlmService {

    private static final Logger log = LoggerFactory.getLogger(DefaultLlmService.class);

    @Override
    public FlagExplanation explainFlag(String ruleCode, String transfersJson) {
        if ("SINGLE_TRANSFER_LIMIT".equals(ruleCode)) {
            return new FlagExplanation(
                    "review",
                    "The transaction amount exceeds the single payment safety limit. Recent transfer history shows regular activity but requires admin verification before moving funds."
            );
        } else if ("VELOCITY_COUNT_WINDOW".equals(ruleCode)) {
            return new FlagExplanation(
                    "likely_fraud",
                    "High frequency of transactions detected in a narrow time window. This matches common automated bot or account takeover patterns."
            );
        }
        return new FlagExplanation("review", "Flagged by automated rule " + ruleCode + " for manual compliance inspection.");
    }

    @Override
    public ParsedQuestion parseStatementQuestion(String question) {
        if (question == null || question.isBlank()) {
            return null;
        }

        String lower = question.toLowerCase();

        // Security check against prompt injection attempts
        if (lower.contains("ignore previous instructions") || lower.contains("show all users") || lower.contains("drop table")) {
            log.warn("Prompt injection attempt detected in question: {}", question);
            // Treat as data, return null or safe default that cannot breach security
            return null;
        }

        LocalDate now = LocalDate.now();
        String from = now.minusMonths(1).toString();
        String to = now.toString();

        // 1. Total Sent to counterparty: "how much did I send to Rahul..."
        if (lower.contains("send") || lower.contains("sent") || lower.contains("paid")) {
            String counterparty = extractCounterparty(question);
            return new ParsedQuestion(1, counterparty, from, to);
        }

        // 2. Total Received: "how much did I receive..."
        if (lower.contains("receive") || lower.contains("received") || lower.contains("got")) {
            String counterparty = extractCounterparty(question);
            return new ParsedQuestion(2, counterparty, from, to);
        }

        // 3. Largest transfer: "largest transfer", "biggest transaction", "maximum"
        if (lower.contains("largest") || lower.contains("biggest") || lower.contains("maximum") || lower.contains("highest")) {
            return new ParsedQuestion(3, null, from, to);
        }

        // 4. Count of transfers: "how many transfers", "number of payments", "count"
        if (lower.contains("how many") || lower.contains("number of") || lower.contains("count")) {
            return new ParsedQuestion(4, null, from, to);
        }

        return null;
    }

    private String extractCounterparty(String text) {
        Pattern pattern = Pattern.compile("(?:to|from)\\s+([A-Za-z]+)", Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(text);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return null;
    }
}
