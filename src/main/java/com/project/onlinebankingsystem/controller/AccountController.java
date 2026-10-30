package com.project.onlinebankingsystem.controller;

import com.project.onlinebankingsystem.dto.AccountRequest;
import com.project.onlinebankingsystem.dto.AccountResponse;
import com.project.onlinebankingsystem.service.AccountService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PathVariable;

import java.security.Principal;
import java.util.List;

@RestController
@RequestMapping("/api/accounts")
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @PostMapping("/create")
    public ResponseEntity<AccountResponse> createAccount(Principal principal, @Valid @RequestBody AccountRequest request) {
        AccountResponse response = accountService.createAccount(principal.getName(), request);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/my")
    public ResponseEntity<List<AccountResponse>> getMyAccounts(Principal principal) {
        List<AccountResponse> accounts = accountService.getAccountsByUsername(principal.getName());
        return ResponseEntity.ok(accounts);
    }

    @GetMapping("/{accountNumber}")
    public ResponseEntity<AccountResponse> getAccountDetails(@PathVariable String accountNumber) {
        AccountResponse response = accountService.getAccountDetails(accountNumber);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{accountNumber}/close")
    public ResponseEntity<AccountResponse> closeAccount(Principal principal, @PathVariable String accountNumber, @org.springframework.web.bind.annotation.RequestParam(required = false) String reason) {
        AccountResponse response = accountService.closeAccount(accountNumber, principal != null ? principal.getName() : null, reason);
        return ResponseEntity.ok(response);
    }
}
