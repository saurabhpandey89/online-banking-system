package com.project.onlinebankingsystem.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class DepositWithdrawRequest {

    @NotBlank(message = "Account number is required")
    private String accountNumber;

    @NotNull(message = "Transaction amount is required")
    @DecimalMin(value = "1.00", message = "Minimum transaction amount is ₹1.00")
    @jakarta.validation.constraints.DecimalMax(value = "1000000.00", message = "Transaction amount cannot exceed the maximum limit of ₹10,00,000.00")
    @jakarta.validation.constraints.Digits(integer = 12, fraction = 2, message = "Amount can have at most 2 decimal places (paise)")
    private BigDecimal amount;

    private String description;

    private String transactionPin;

    private String idempotencyKey;

    public DepositWithdrawRequest(String accountNumber, BigDecimal amount, String description, String transactionPin) {
        this.accountNumber = accountNumber;
        this.amount = amount;
        this.description = description;
        this.transactionPin = transactionPin;
    }
}
