package com.project.onlinebankingsystem.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "beneficiaries")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Beneficiary {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String accountNumber;

    @Column(nullable = false)
    private String ifscCode;

    private String bankName;

    @Builder.Default
    @Column(nullable = true)
    private Boolean verified = false;

    public boolean isVerified() {
        return verified != null && verified;
    }

    public Boolean getVerified() {
        return verified != null && verified;
    }

    @Builder.Default
    private String status = "COOLING_OFF";

    private LocalDateTime coolingEndsAt;

    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (coolingEndsAt == null) {
            coolingEndsAt = LocalDateTime.now().plusMinutes(15);
        }
        if (status == null) {
            status = "COOLING_OFF";
        }
    }
}
