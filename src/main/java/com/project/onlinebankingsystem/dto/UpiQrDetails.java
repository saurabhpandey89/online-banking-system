package com.project.onlinebankingsystem.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpiQrDetails {
    private String vpa;
    private String payeeName;
    private String merchantCode;
    private String transactionRef;
    private String terminalId;
    private String note;
    private BigDecimal amount;
    private String currency;
    private boolean fixedAmount;
    private String rawQrData;
}
