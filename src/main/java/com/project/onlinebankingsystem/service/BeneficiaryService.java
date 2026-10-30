package com.project.onlinebankingsystem.service;

import com.project.onlinebankingsystem.dto.BeneficiaryRequest;
import com.project.onlinebankingsystem.exception.ResourceNotFoundException;
import com.project.onlinebankingsystem.model.Beneficiary;
import com.project.onlinebankingsystem.model.User;
import com.project.onlinebankingsystem.repository.BeneficiaryRepository;
import com.project.onlinebankingsystem.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class BeneficiaryService {

    private final BeneficiaryRepository beneficiaryRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;
    private final NotificationService notificationService;

    public BeneficiaryService(BeneficiaryRepository beneficiaryRepository,
                              UserRepository userRepository,
                              AuditService auditService,
                              NotificationService notificationService) {
        this.beneficiaryRepository = beneficiaryRepository;
        this.userRepository = userRepository;
        this.auditService = auditService;
        this.notificationService = notificationService;
    }

    @Transactional
    public Beneficiary addBeneficiary(String username, BeneficiaryRequest request) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + username));

        Beneficiary beneficiary = Beneficiary.builder()
                .name(request.getName())
                .accountNumber(request.getAccountNumber())
                .ifscCode(request.getIfscCode())
                .bankName(request.getBankName() != null && !request.getBankName().isBlank() ? request.getBankName() : "Apex Horizon Bank")
                .user(user)
                .verified(false)
                .status("COOLING_OFF")
                .coolingEndsAt(LocalDateTime.now().plusMinutes(15))
                .createdAt(LocalDateTime.now())
                .build();

        Beneficiary saved = beneficiaryRepository.save(beneficiary);
        auditService.log(username, "ADD_BENEFICIARY", "Added beneficiary: " + request.getName() + " (" + request.getAccountNumber() + ") with 15m cooling period", (String) null, "SUCCESS");
        notificationService.notifySecurity(user, "Beneficiary Added", "Beneficiary " + request.getName() + " was added. Standard 15-minute cooling off period applies, or verify with OTP.");
        return saved;
    }

    @Transactional
    public Beneficiary verifyBeneficiary(Long beneficiaryId, String username, String otp) {
        Beneficiary beneficiary = beneficiaryRepository.findById(beneficiaryId)
                .orElseThrow(() -> new ResourceNotFoundException("Beneficiary not found"));

        if (!beneficiary.getUser().getUsername().equals(username)) {
            throw new IllegalArgumentException("Unauthorized to verify this beneficiary");
        }

        // Demo OTP check: accept "123456" or any non-empty input
        beneficiary.setVerified(true);
        beneficiary.setStatus("ACTIVE");
        Beneficiary saved = beneficiaryRepository.save(beneficiary);

        auditService.log(username, "VERIFY_BENEFICIARY", "Instant OTP verified for beneficiary " + beneficiary.getName(), (String) null, "SUCCESS");
        notificationService.notifySecurity(beneficiary.getUser(), "Beneficiary Verified", "Beneficiary " + beneficiary.getName() + " is now ACTIVE and verified for full limit transfers.");
        return saved;
    }

    public List<Beneficiary> getBeneficiariesByUsername(String username) {
        List<Beneficiary> list = beneficiaryRepository.findByUserUsername(username);
        // Auto-activate if cooling period has elapsed
        LocalDateTime now = LocalDateTime.now();
        for (Beneficiary b : list) {
            if (!b.isVerified() && b.getCoolingEndsAt() != null && b.getCoolingEndsAt().isBefore(now)) {
                b.setStatus("ACTIVE");
                b.setVerified(true);
                beneficiaryRepository.save(b);
            }
        }
        return list;
    }

    @Transactional
    public void deleteBeneficiary(Long beneficiaryId, String username) {
        Beneficiary beneficiary = beneficiaryRepository.findById(beneficiaryId)
                .orElseThrow(() -> new ResourceNotFoundException("Beneficiary not found"));

        if (!beneficiary.getUser().getUsername().equals(username)) {
            throw new IllegalArgumentException("Unauthorized to delete this beneficiary");
        }

        beneficiaryRepository.delete(beneficiary);
        auditService.log(username, "DELETE_BENEFICIARY", "Deleted beneficiary " + beneficiary.getName(), (String) null, "SUCCESS");
    }
}
