package com.project.onlinebankingsystem.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TransactionResponse {
    private Long id;
    private String transactionReference;
    private String sourceAccountNumber;
    private String destinationAccountNumber;
    private BigDecimal amount;
    private String type;
    private String status;
    private String description;
    private String failureReason;
    private String reversalReference;
    private String cancellationReason;
    private String idempotencyKey;
    private boolean idempotentReplay;
    private String npciRrn;
    private String payeeVpa;
    private String payeeName;
    private String merchantCode;
    private LocalDateTime completedAt;
    private LocalDateTime timestamp;
}
