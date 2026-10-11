package com.wallet.service;

public interface LlmService {

    record FlagExplanation(
            String evaluation, // 'likely_ok', 'review', 'likely_fraud'
            String explanation
    ) {}

    record ParsedQuestion(
            Integer type,           // 1: Total sent, 2: Total received, 3: Largest transfer, 4: Count of transfers
            String counterparty,    // e.g. "Rahul"
            String fromDate,        // ISO string "YYYY-MM-DD"
            String toDate           // ISO string "YYYY-MM-DD"
    ) {}

    FlagExplanation explainFlag(String ruleCode, String transfersJson);

    ParsedQuestion parseStatementQuestion(String question);
}
