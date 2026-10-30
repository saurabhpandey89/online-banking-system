package com.project.onlinebankingsystem.controller;

import com.project.onlinebankingsystem.dto.UpiAccountResponse;
import com.project.onlinebankingsystem.dto.UpiPayeeValidationResponse;
import com.project.onlinebankingsystem.dto.UpiPaymentRequest;
import com.project.onlinebankingsystem.dto.UpiPaymentResponse;
import com.project.onlinebankingsystem.model.Transaction;
import com.project.onlinebankingsystem.repository.TransactionRepository;
import com.project.onlinebankingsystem.service.UpiService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/upi")
public class UpiController {

    private final UpiService upiService;
    private final TransactionRepository transactionRepository;

    public UpiController(UpiService upiService, TransactionRepository transactionRepository) {
        this.upiService = upiService;
        this.transactionRepository = transactionRepository;
    }

    /**
     * Resolves basic account details by UPI ID (Backward-compatible).
     */
    @GetMapping("/resolve")
    public ResponseEntity<UpiAccountResponse> resolveUpi(@RequestParam String upiId) {
        UpiAccountResponse response = upiService.findAccountByUpiId(upiId);
        return ResponseEntity.ok(response);
    }

    /**
     * Real UPI Payee & QR Validation (VPA Verification & Account Enquiry):
     * Parses QR, verifies Payee Name, Merchant Category, On-Us / Off-Us routing, and session RRN.
     */
    @PostMapping("/validate")
    public ResponseEntity<UpiPayeeValidationResponse> validatePayee(@RequestBody Map<String, String> payload) {
        String qrData = payload.get("qrData");
        if (qrData == null || qrData.isBlank()) {
            qrData = payload.get("vpa");
        }
        if (qrData == null || qrData.isBlank()) {
            qrData = payload.get("upiId");
        }
        if (qrData == null || qrData.isBlank()) {
            return ResponseEntity.badRequest().body(UpiPayeeValidationResponse.builder()
                    .valid(false)
                    .validationMessage("No QR data or UPI ID provided.")
                    .build());
        }

        UpiPayeeValidationResponse response = upiService.validatePayee(qrData);
        return ResponseEntity.ok(response);
    }

    /**
     * Real UPI Payment Execution:
     * Executes payment with Idempotency, 2FA TPIN/MPIN, duplicate check, and instant receipt.
     */
    @PostMapping("/pay")
    public ResponseEntity<UpiPaymentResponse> payViaUpi(
            @RequestHeader(value = "X-Idempotency-Key", required = false) String idempHeader,
            @Valid @RequestBody UpiPaymentRequest request,
            Principal principal) {

        if (idempHeader != null && !idempHeader.isBlank() && (request.getIdempotencyKey() == null || request.getIdempotencyKey().isBlank())) {
            request.setIdempotencyKey(idempHeader.trim());
        }

        String actor = principal != null ? principal.getName() : "USER";
        UpiPaymentResponse response = upiService.executeUpiPayment(request, actor);

        return ResponseEntity.ok()
                .header("X-Idempotent-Replay", String.valueOf(response.isIdempotentReplay()))
                .body(response);
    }

    /**
     * Queries UPI Payment status by 12-digit NPCI RRN.
     */
    @GetMapping("/status/{rrn}")
    public ResponseEntity<UpiPaymentResponse> getUpiStatus(@PathVariable String rrn) {
        Optional<Transaction> txOpt = transactionRepository.findByNpciRrn(rrn);
        if (txOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        Transaction tx = txOpt.get();
        UpiPaymentResponse response = UpiPaymentResponse.builder()
                .status(tx.getStatus().name())
                .responseCode(tx.getStatus() == com.project.onlinebankingsystem.enums.TransactionStatus.SUCCESS ? "00" : "FAIL")
                .message("Transaction status: " + tx.getStatus().name())
                .transactionReference(tx.getTransactionReference())
                .npciRrn(tx.getNpciRrn())
                .amount(tx.getAmount())
                .formattedAmount(upiService.formatAmount(tx.getAmount()))
                .payeeVpa(tx.getPayeeVpa())
                .payeeName(tx.getPayeeName())
                .timestamp(tx.getCompletedAt() != null ? tx.getCompletedAt() : tx.getTimestamp())
                .build();

        return ResponseEntity.ok(response);
    }
}