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
public class AccountResponse {

    private Long id;

    private String accountNumber;

    private String upiId;

    private String ifscCode;

    private String accountType;

    private BigDecimal balance;

    private String status;

    private LocalDateTime createdAt;

    private String userUsername;

    private String userFullName;

    private String userEmail;

    private BigDecimal perTransactionLimit;
    private BigDecimal dailyLimit;
    private boolean hasTransactionPin;
}
