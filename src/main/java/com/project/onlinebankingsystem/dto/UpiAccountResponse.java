package com.project.onlinebankingsystem.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpiAccountResponse {

    private String upiId;

    private String accountNumber;

    private String accountHolderName;

    private String ifscCode;

    private String bankName;
}