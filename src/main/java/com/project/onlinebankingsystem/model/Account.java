package com.project.onlinebankingsystem.model;

import com.project.onlinebankingsystem.enums.AccountStatus;
import com.project.onlinebankingsystem.enums.AccountType;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * ============================================================================
 *                       ACCOUNT ENTITY (accounts table)
 * ============================================================================
 * Represents a bank account owned by a user.
 * 
 * KEY FIELDS:
 * - accountNumber: Unique 10-digit account number (e.g., 5739153290).
 * - upiId: Virtual payment address (e.g., user@obsbank) for instant QR payments.
 * - balance: Current available balance in Rupees (BigDecimal for exact currency precision).
 * - status: ACTIVE, FROZEN (temporarily locked by user), or SUSPENDED.
 * - user: The owner of this bank account (@ManyToOne relationship).
 * ============================================================================
 */
@Entity
@Table(name = "accounts")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    private Long version;

    @Column(unique = true, nullable = false)
    private String accountNumber;

    @Column(unique = true)
    private String upiId;

    @Column(nullable = false)
    private String ifscCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AccountType accountType;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal balance;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AccountStatus status;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @com.fasterxml.jackson.annotation.JsonIgnore
    private String transactionPin;

    public boolean hasTransactionPin() {
        return transactionPin != null && !transactionPin.trim().isEmpty();
    }

    public boolean isHasTransactionPin() {
        return transactionPin != null && !transactionPin.trim().isEmpty();
    }

    public Boolean getHasTransactionPin() {
        return transactionPin != null && !transactionPin.trim().isEmpty();
    }

    @Builder.Default
    @Column(precision = 15, scale = 2)
    private BigDecimal perTransactionLimit = new BigDecimal("50000.00");

    @Builder.Default
    @Column(precision = 15, scale = 2)
    private BigDecimal dailyLimit = new BigDecimal("100000.00");

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (balance == null) {
            balance = BigDecimal.ZERO;
        }
        if (status == null) {
            status = AccountStatus.ACTIVE;
        }
        if (perTransactionLimit == null) {
            perTransactionLimit = new BigDecimal("50000.00");
        }
        if (dailyLimit == null) {
            dailyLimit = new BigDecimal("100000.00");
        }
        if (version == null) {
            version = 0L;
        }
    }
}
