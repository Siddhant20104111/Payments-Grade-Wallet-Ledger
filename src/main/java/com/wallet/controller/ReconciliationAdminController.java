package com.wallet.controller;

import com.wallet.service.ReconciliationService;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/admin/reconciliation")
public class ReconciliationAdminController {

    private final ReconciliationService reconciliationService;
    private final JdbcTemplate jdbcTemplate;

    public ReconciliationAdminController(ReconciliationService reconciliationService, JdbcTemplate jdbcTemplate) {
        this.reconciliationService = reconciliationService;
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostMapping("/run")
    public ResponseEntity<ReconciliationService.ReconciliationResult> triggerManualReconciliation() {
        ReconciliationService.ReconciliationResult result = reconciliationService.runReconciliationAudit();
        return ResponseEntity.ok(result);
    }

    @GetMapping("/runs")
    public ResponseEntity<List<Map<String, Object>>> getRecentRuns() {
        List<Map<String, Object>> runs = jdbcTemplate.queryForList(
                "SELECT id, started_at, finished_at, status, total_transfers_checked, total_discrepancies_found " +
                        "FROM reconciliation_runs ORDER BY id DESC LIMIT 10"
        );
        return ResponseEntity.ok(runs);
    }

    @GetMapping("/runs/{id}/issues")
    public ResponseEntity<List<Map<String, Object>>> getRunIssues(@PathVariable("id") Long runId) {
        List<Map<String, Object>> issues = jdbcTemplate.queryForList(
                "SELECT id, check_type, entity_id, expected_value, actual_value, detected_at " +
                        "FROM reconciliation_issues WHERE run_id = ? ORDER BY id ASC",
                runId
        );
        return ResponseEntity.ok(issues);
    }
}
