package com.project.onlinebankingsystem.controller;

import com.project.onlinebankingsystem.dto.BeneficiaryRequest;
import com.project.onlinebankingsystem.model.Beneficiary;
import com.project.onlinebankingsystem.service.BeneficiaryService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;

@RestController
@RequestMapping("/api/beneficiaries")
public class BeneficiaryController {

    private final BeneficiaryService beneficiaryService;

    public BeneficiaryController(BeneficiaryService beneficiaryService) {
        this.beneficiaryService = beneficiaryService;
    }

    @GetMapping
    public ResponseEntity<List<Beneficiary>> getMyBeneficiaries(Principal principal) {
        List<Beneficiary> beneficiaries = beneficiaryService.getBeneficiariesByUsername(principal.getName());
        return ResponseEntity.ok(beneficiaries);
    }

    @PostMapping
    public ResponseEntity<Beneficiary> addBeneficiary(Principal principal, @Valid @RequestBody BeneficiaryRequest request) {
        Beneficiary beneficiary = beneficiaryService.addBeneficiary(principal.getName(), request);
        return ResponseEntity.ok(beneficiary);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteBeneficiary(Principal principal, @PathVariable Long id) {
        beneficiaryService.deleteBeneficiary(id, principal.getName());
        return ResponseEntity.noContent().build();
    }
}
