package com.project.onlinebankingsystem.model;

import com.project.onlinebankingsystem.enums.Gender;
import com.project.onlinebankingsystem.enums.Role;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * ============================================================================
 *                         USER ENTITY (users table)
 * ============================================================================
 * Represents a customer or administrator in the banking system.
 * 
 * KEY FIELDS:
 * - username, password: Used for logging into the dashboard.
 * - pan, aadhar, phone: Official Indian KYC identity documents (must be unique).
 * - role: ROLE_CUSTOMER (normal user) or ROLE_ADMIN (bank manager).
 * - accounts: List of bank accounts owned by this user (1 user can have multiple accounts).
 * ============================================================================
 */
@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String username;

    @Column(nullable = false)
    private String password;

    @Column(nullable = false)
    private String fullName;

    @Column(unique = true, nullable = false)
    private String email;

    @Column(unique = true, nullable = false)
    private String phone;

    private String fatherName;
    private String motherName;
    private LocalDate dob;

    @Column(unique = true)
    private String pan;

    @Column(unique = true)
    private String aadhar;

    @Enumerated(EnumType.STRING)
    private Gender gender;

    private String address;

    @Builder.Default
    @Column(nullable = true)
    private Boolean emailVerified = true;

    @Builder.Default
    @Column(nullable = true)
    private Boolean phoneVerified = true;

    @Builder.Default
    @Column(nullable = true)
    private Boolean twoFactorEnabled = false;

    @Builder.Default
    @Column(nullable = true)
    private Integer failedLoginAttempts = 0;

    public Boolean getEmailVerified() {
        return emailVerified != null && emailVerified;
    }

    public boolean isEmailVerified() {
        return emailVerified != null && emailVerified;
    }

    public Boolean getPhoneVerified() {
        return phoneVerified != null && phoneVerified;
    }

    public boolean isPhoneVerified() {
        return phoneVerified != null && phoneVerified;
    }

    public Boolean getTwoFactorEnabled() {
        return twoFactorEnabled != null && twoFactorEnabled;
    }

    public boolean isTwoFactorEnabled() {
        return twoFactorEnabled != null && twoFactorEnabled;
    }

    public Integer getFailedLoginAttempts() {
        return failedLoginAttempts != null ? failedLoginAttempts : 0;
    }

    private LocalDateTime lockoutUntil;

    private String otpCode;
    private LocalDateTime otpExpiry;

    private String lastLoginIp;
    private String lastLoginDevice;
    private LocalDateTime lastLoginTime;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    @Builder.Default
    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Account> accounts = new ArrayList<>();

    @Builder.Default
    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Beneficiary> beneficiaries = new ArrayList<>();
}
