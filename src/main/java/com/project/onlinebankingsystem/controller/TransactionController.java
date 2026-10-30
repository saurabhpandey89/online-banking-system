package com.project.onlinebankingsystem.controller;

import com.project.onlinebankingsystem.dto.DepositWithdrawRequest;
import com.project.onlinebankingsystem.dto.TransactionResponse;
import com.project.onlinebankingsystem.dto.TransferRequest;
import com.project.onlinebankingsystem.service.TransactionService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;

@RestController
@RequestMapping("/api/transactions")
public class TransactionController {

    private final TransactionService transactionService;

    public TransactionController(TransactionService transactionService) {
        this.transactionService = transactionService;
    }

    @PostMapping("/deposit")
    public ResponseEntity<TransactionResponse> deposit(
            @RequestHeader(value = "X-Idempotency-Key", required = false) String idempHeader,
            @Valid @RequestBody DepositWithdrawRequest request) {
        String key = (idempHeader != null && !idempHeader.isBlank()) ? idempHeader : request.getIdempotencyKey();
        TransactionResponse response = transactionService.deposit(request.getAccountNumber(), request.getAmount(), "Online Cash Deposit", key);
        return ResponseEntity.ok()
                .header("X-Idempotent-Replay", String.valueOf(response.isIdempotentReplay()))
                .body(response);
    }

    @PostMapping("/withdraw")
    public ResponseEntity<TransactionResponse> withdraw(
            @RequestHeader(value = "X-Idempotency-Key", required = false) String idempHeader,
            @Valid @RequestBody DepositWithdrawRequest request) {
        String key = (idempHeader != null && !idempHeader.isBlank()) ? idempHeader : request.getIdempotencyKey();
        TransactionResponse response = transactionService.withdraw(
                request.getAccountNumber(),
                request.getAmount(),
                request.getDescription() != null && !request.getDescription().isBlank() ? request.getDescription() : "Online Cash Withdrawal",
                request.getTransactionPin(),
                key
        );
        return ResponseEntity.ok()
                .header("X-Idempotent-Replay", String.valueOf(response.isIdempotentReplay()))
                .body(response);
    }

    @PostMapping("/transfer")
    public ResponseEntity<TransactionResponse> transfer(
            @RequestHeader(value = "X-Idempotency-Key", required = false) String idempHeader,
            @Valid @RequestBody TransferRequest request) {
        String key = (idempHeader != null && !idempHeader.isBlank()) ? idempHeader : request.getIdempotencyKey();
        TransactionResponse response = transactionService.transfer(
                request.getSourceAccountNumber(),
                request.getDestinationAccountNumber(),
                request.getAmount(),
                request.getDescription(),
                request.getTransactionPin(),
                key
        );
        return ResponseEntity.ok()
                .header("X-Idempotent-Replay", String.valueOf(response.isIdempotentReplay()))
                .body(response);
    }

    @PostMapping("/pending")
    public ResponseEntity<TransactionResponse> initiatePending(@Valid @RequestBody TransferRequest request) {
        TransactionResponse response = transactionService.initiatePendingTransfer(
                request.getSourceAccountNumber(),
                request.getDestinationAccountNumber(),
                request.getAmount(),
                request.getDescription(),
                request.getTransactionPin()
        );
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{reference}/approve")
    public ResponseEntity<TransactionResponse> approveTransaction(@PathVariable String reference, Principal principal) {
        String actor = principal != null ? principal.getName() : "ADMIN";
        TransactionResponse response = transactionService.approveTransaction(reference, actor);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{reference}/cancel")
    public ResponseEntity<TransactionResponse> cancelTransaction(
            @PathVariable String reference,
            @RequestParam(required = false, defaultValue = "Cancelled by user") String reason,
            Principal principal) {
        String actor = principal != null ? principal.getName() : "USER";
        TransactionResponse response = transactionService.cancelTransaction(reference, reason, actor);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{reference}/reject")
    public ResponseEntity<TransactionResponse> rejectTransaction(
            @PathVariable String reference,
            @RequestParam(required = false, defaultValue = "Rejected by administrator") String reason,
            Principal principal) {
        String actor = principal != null ? principal.getName() : "ADMIN";
        TransactionResponse response = transactionService.rejectTransaction(reference, reason, actor);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{reference}/reverse")
    public ResponseEntity<TransactionResponse> reverseTransaction(@PathVariable String reference, Principal principal) {
        String actor = principal != null ? principal.getName() : "ADMIN";
        TransactionResponse response = transactionService.reverseTransaction(reference, actor);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/pending")
    public ResponseEntity<List<TransactionResponse>> getPendingTransactions() {
        return ResponseEntity.ok(transactionService.getPendingTransactions());
    }

    @GetMapping("/status/{status}")
    public ResponseEntity<List<TransactionResponse>> getTransactionsByStatus(@PathVariable String status) {
        com.project.onlinebankingsystem.enums.TransactionStatus txStatus = 
                com.project.onlinebankingsystem.enums.TransactionStatus.valueOf(status.toUpperCase());
        return ResponseEntity.ok(transactionService.getTransactionsByStatus(txStatus));
    }

    @GetMapping("/history/{accountNumber}")
    public ResponseEntity<List<TransactionResponse>> getHistory(@PathVariable String accountNumber) {
        List<TransactionResponse> history = transactionService.getTransactionHistory(accountNumber);
        return ResponseEntity.ok(history);
    }
}
