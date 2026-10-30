package com.project.onlinebankingsystem.dto;

import jakarta.validation.constraints.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RegisterRequest {

    @NotBlank(message = "Username is required")
    @Size(min = 4, max = 20, message = "Username must be between 4 and 20 characters")
    private String username;

    @NotBlank(message = "Password is required")
    @Size(min = 6, message = "Password must be at least 6 characters")
    private String password;

    @NotBlank(message = "Full name is required")
    private String fullName;

    @NotBlank(message = "Email is required")
    @Email(message = "Invalid email format")
    private String email;

    @NotBlank(message = "Phone number is required")
    @Pattern(regexp = "^[0-9]{10}$", message = "Phone number must be exactly 10 digits")
    private String phone;

    private String fatherName;
    private String motherName;

    @NotNull(message = "Date of Birth is required")
    @Past(message = "Date of Birth must be in the past")
    private LocalDate dob;

    @NotBlank(message = "PAN card number is required")
    @Pattern(regexp = "^[A-Z]{5}[0-9]{4}[A-Z]{1}$", message = "Invalid PAN format. Example: ABCDE1234F")
    private String pan;

    @NotBlank(message = "Aadhar number is required")
    @Pattern(regexp = "^[0-9]{12}$", message = "Aadhar must be exactly 12 digits")
    private String aadhar;

    @NotBlank(message = "Gender is required")
    private String gender;

    private String address;

    @NotBlank(message = "Account Type is required")
    private String accountType; // SAVING, CURRENT

    @DecimalMin(value = "0.0", inclusive = true, message = "Initial balance cannot be negative")
    private BigDecimal initialBalance;
}