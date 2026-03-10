package com.portfolio.ecosystem.controller;

import com.portfolio.ecosystem.dto.AccountResponse;
import com.portfolio.ecosystem.dto.TransactionRequest;
import com.portfolio.ecosystem.entity.Transaction;
import com.portfolio.ecosystem.service.TransactionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class TransactionController {

    private final TransactionService transactionService;

    @PostMapping("/transactions/debit")
    public ResponseEntity<Transaction> debit(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody TransactionRequest request) {
        return ResponseEntity.ok(transactionService.processTransaction(request, idempotencyKey, false));
    }

    @PostMapping("/transactions/credit")
    public ResponseEntity<Transaction> credit(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody TransactionRequest request) {
        return ResponseEntity.ok(transactionService.processTransaction(request, idempotencyKey, true));
    }

    @GetMapping("/accounts/{accountId}/balance")
    public ResponseEntity<AccountResponse> getBalance(@PathVariable UUID accountId) {
        return ResponseEntity.ok(transactionService.getBalance(accountId));
    }
}

