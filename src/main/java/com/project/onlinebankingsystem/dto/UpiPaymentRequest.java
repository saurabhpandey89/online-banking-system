package com.project.onlinebankingsystem.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpiPaymentRequest {

    @NotBlank(message = "Debiting account number is required")
    private String sourceAccountNumber;

    @NotBlank(message = "Payee UPI ID (VPA) is required")
    private String payeeVpa;

    private String payeeName;

    @NotNull(message = "Payment amount is required")
    @DecimalMin(value = "1.00", message = "Minimum UPI transfer amount is ₹1.00")
    @Digits(integer = 12, fraction = 2, message = "Amount cannot have more than 2 decimal places")
    private BigDecimal amount;

    private String pin;

    private String description;

    private String npciRrn;

    private String idempotencyKey;

    private String merchantCode;

    private boolean fixedAmount;
}
