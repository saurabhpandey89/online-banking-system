package com.project.onlinebankingsystem.dto;

import com.project.onlinebankingsystem.enums.UpiPayeeType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpiPayeeValidationResponse {
    private boolean valid;
    private String vpa;
    private String verifiedName;
    private UpiPayeeType payeeType;
    private String merchantCategory;
    private String merchantCode;
    private String bankName;
    private String ifscCode;
    private boolean isOnUs;
    private BigDecimal fixedAmount;
    private BigDecimal maxAllowedAmount;
    private String suggestedRrn;
    private String transactionNote;
    private String validationMessage;
    private String warning;
    private String maskedAccountNumber;
}
