package com.project.onlinebankingsystem.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpiPaymentResponse {
    private String status;
    private String responseCode;
    private String message;
    private String transactionReference;
    private String npciRrn;
    private BigDecimal amount;
    private String formattedAmount;
    private String sourceAccountNumber;
    private BigDecimal sourceAccountBalance;
    private String payeeVpa;
    private String payeeName;
    private String bankName;
    private String idempotencyKey;
    private boolean idempotentReplay;
    private String failureReason;
    private LocalDateTime timestamp;
}
