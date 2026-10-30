package com.project.onlinebankingsystem.model;

import com.project.onlinebankingsystem.enums.TransactionStatus;
import com.project.onlinebankingsystem.enums.TransactionType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * ============================================================================
 *                   TRANSACTION ENTITY (transactions table)
 * ============================================================================
 * Represents a financial record (Deposit, Withdrawal, Transfer, or UPI Payment).
 * 
 * KEY FIELDS:
 * - transactionReference: Unique UUID reference string (e.g., 9b1deb4d-3b7d-4bad...).
 * - sourceAccount: The account sending the money (null for cash deposits).
 * - destinationAccount: The account receiving the money (null for cash withdrawals).
 * - amount: Amount in Rupees.
 * - type: DEPOSIT, WITHDRAWAL, TRANSFER, UPI_PAYMENT.
 * - status: SUCCESS or FAILED.
 * - timestamp: Exact date and time when the transaction took place.
 * ============================================================================
 */
@Entity
@Table(name = "transactions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String transactionReference;

    @Column(name = "idempotency_key", unique = true)
    private String idempotencyKey;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_account_id")
    private Account sourceAccount;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "destination_account_id")
    private Account destinationAccount;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 50)
    private TransactionType type;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 50)
    private TransactionStatus status;

    private String description;

    private String failureReason;

    private String reversalReference;

    private String cancellationReason;

    @Column(name = "npci_rrn", length = 12)
    private String npciRrn;

    @Column(name = "payee_vpa")
    private String payeeVpa;

    @Column(name = "payee_name")
    private String payeeName;

    @Column(name = "merchant_code")
    private String merchantCode;

    private LocalDateTime completedAt;

    @Column(nullable = false)
    private LocalDateTime timestamp;

    @PrePersist
    protected void onCreate() {
        if (timestamp == null) {
            timestamp = LocalDateTime.now();
        }
        if (status == null) {
            status = TransactionStatus.PENDING;
        }
    }

    public boolean isPending() {
        return status == TransactionStatus.PENDING;
    }

    public boolean isSuccess() {
        return status == TransactionStatus.SUCCESS;
    }

    public boolean isFailed() {
        return status == TransactionStatus.FAILED;
    }

    public boolean isReversed() {
        return status == TransactionStatus.REVERSED;
    }

    public boolean isCancelled() {
        return status == TransactionStatus.CANCELLED;
    }

    public boolean canBeCancelled() {
        return status == TransactionStatus.PENDING;
    }

    public boolean canBeReversed() {
        return status == TransactionStatus.SUCCESS && (reversalReference == null || reversalReference.isBlank());
    }
}
