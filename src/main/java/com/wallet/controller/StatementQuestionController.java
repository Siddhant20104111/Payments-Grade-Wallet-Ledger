package com.wallet.controller;

import com.wallet.service.StatementQuestionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/users/{id}/statement")
public class StatementQuestionController {

    private final StatementQuestionService statementQuestionService;

    public StatementQuestionController(StatementQuestionService statementQuestionService) {
        this.statementQuestionService = statementQuestionService;
    }

    @PostMapping("/ask")
    public ResponseEntity<StatementQuestionService.StatementAnswer> askStatementQuestion(
            @PathVariable("id") Long userId,
            @RequestBody Map<String, String> body
    ) {
        String question = body.get("question");
        StatementQuestionService.StatementAnswer answer = statementQuestionService.answerStatementQuestion(userId, question);
        return ResponseEntity.ok(answer);
    }
}
