package com.wallet.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class StatementQuestionService {

    private static final Logger log = LoggerFactory.getLogger(StatementQuestionService.class);

    private final JdbcTemplate jdbcTemplate;
    private final LlmService llmService;

    public record StatementAnswer(
            String question,
            String answerText,
            Long resultValuePaise,
            Long count,
            List<Long> supportingTransferIds,
            boolean isSupported
    ) {}

    public StatementQuestionService(JdbcTemplate jdbcTemplate, LlmService llmService) {
        this.jdbcTemplate = jdbcTemplate;
        this.llmService = llmService;
    }

    public StatementAnswer answerStatementQuestion(Long userId, String question) {
        // Step 1: LLM parses natural language into structured JSON intent
        LlmService.ParsedQuestion parsed = llmService.parseStatementQuestion(question);

        // Step 2: Validate JSON. If unsupported or prompt injection attempt, return safe fallback
        if (parsed == null || parsed.type() == null || parsed.type() < 1 || parsed.type() > 4) {
            return new StatementAnswer(
                    question,
                    "I can only answer four types of statement questions: (1) Total sent to a person in a date range, " +
                            "(2) Total received in a date range, (3) Largest transfer, and (4) Number of transfers.",
                    0L, 0L, List.of(), false
            );
        }

        // Step 3 & 4: Execute parameterized SQL restricted strictly to the caller's accounts!
        final List<Long> transferIds = new ArrayList<>();
        long calculatedValue = 0L;
        long transferCount = 0L;
        String answerSummary;

        switch (parsed.type()) {
            case 1: { // Total sent to counterparty
                String sql = "SELECT t.id, t.amount FROM transfers t " +
                        "JOIN accounts a ON t.user_id = a.user_id " +
                        "JOIN ledger_entries le ON t.id = le.transfer_id AND le.account_id = a.id " +
                        "WHERE t.user_id = ? AND le.entry_type = 'DEBIT' AND t.status = 'COMPLETED'";

                List<TransferRow> rows = jdbcTemplate.query(
                        sql,
                        (rs, rowNum) -> new TransferRow(rs.getLong("id"), rs.getLong("amount")),
                        userId
                );
                for (TransferRow r : rows) {
                    transferIds.add(r.id());
                    calculatedValue += r.amount();
                }
                transferCount = transferIds.size();
                answerSummary = String.format("You sent a total of ₹%.2f across %d transfers.",
                        calculatedValue / 100.0, transferCount);
                break;
            }
            case 2: { // Total received
                String sql = "SELECT t.id, le.amount FROM transfers t " +
                        "JOIN accounts a ON a.user_id = ? " +
                        "JOIN ledger_entries le ON t.id = le.transfer_id AND le.account_id = a.id " +
                        "WHERE le.entry_type = 'CREDIT' AND t.status = 'COMPLETED'";

                List<TransferRow> rows = jdbcTemplate.query(
                        sql,
                        (rs, rowNum) -> new TransferRow(rs.getLong("id"), rs.getLong("amount")),
                        userId
                );
                for (TransferRow r : rows) {
                    transferIds.add(r.id());
                    calculatedValue += r.amount();
                }
                transferCount = transferIds.size();
                answerSummary = String.format("You received a total of ₹%.2f across %d transfers.",
                        calculatedValue / 100.0, transferCount);
                break;
            }
            case 3: { // Largest transfer
                String sql = "SELECT id, amount FROM transfers WHERE user_id = ? AND status = 'COMPLETED' " +
                        "ORDER BY amount DESC LIMIT 1";
                try {
                    TransferRow largest = jdbcTemplate.queryForObject(
                            sql,
                            (rs, rowNum) -> new TransferRow(rs.getLong("id"), rs.getLong("amount")),
                            userId
                    );
                    if (largest != null) {
                        transferIds.add(largest.id());
                        calculatedValue = largest.amount();
                        transferCount = 1;
                        answerSummary = String.format("Your largest transfer was ₹%.2f.", calculatedValue / 100.0);
                    } else {
                        answerSummary = "You have no completed transfers.";
                    }
                } catch (Exception e) {
                    answerSummary = "You have no completed transfers.";
                }
                break;
            }
            case 4: { // Count of transfers
                String sql = "SELECT id FROM transfers WHERE user_id = ? AND status = 'COMPLETED'";
                List<Long> ids = jdbcTemplate.query(sql, (rs, rowNum) -> rs.getLong("id"), userId);
                transferIds.addAll(ids);
                transferCount = transferIds.size();
                answerSummary = String.format("You made %d completed transfers.", transferCount);
                break;
            }
            default:
                answerSummary = "Unsupported query type.";
        }

        return new StatementAnswer(question, answerSummary, calculatedValue, transferCount, transferIds, true);
    }

    private record TransferRow(Long id, Long amount) {}
}
