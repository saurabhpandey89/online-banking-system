package com.project.onlinebankingsystem.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class BeneficiaryRequest {

    @NotBlank(message = "Beneficiary name is required")
    private String name;

    @NotBlank(message = "Account number is required")
    private String accountNumber;

    @NotBlank(message = "IFSC code is required")
    private String ifscCode;

    private String bankName;
}
