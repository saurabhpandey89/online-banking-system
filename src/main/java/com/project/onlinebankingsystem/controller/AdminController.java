package com.project.onlinebankingsystem.controller;

import com.project.onlinebankingsystem.dto.AccountResponse;
import com.project.onlinebankingsystem.dto.TransactionResponse;
import com.project.onlinebankingsystem.enums.AccountStatus;
import com.project.onlinebankingsystem.exception.ResourceNotFoundException;
import com.project.onlinebankingsystem.model.Account;
import com.project.onlinebankingsystem.model.User;
import com.project.onlinebankingsystem.repository.AccountRepository;
import com.project.onlinebankingsystem.service.AccountService;
import com.project.onlinebankingsystem.service.TransactionService;
import com.project.onlinebankingsystem.service.UserService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final UserService userService;
    private final AccountService accountService;
    private final TransactionService transactionService;
    private final AccountRepository accountRepository;

    public AdminController(UserService userService, AccountService accountService, 
                           TransactionService transactionService, AccountRepository accountRepository) {
        this.userService = userService;
        this.accountService = accountService;
        this.transactionService = transactionService;
        this.accountRepository = accountRepository;
    }

    @GetMapping("/users")
    public ResponseEntity<List<User>> getAllUsers() {
        return ResponseEntity.ok(userService.getAllUsers());
    }

    @GetMapping("/accounts")
    public ResponseEntity<List<AccountResponse>> getAllAccounts() {
        return ResponseEntity.ok(accountService.getAllAccounts());
    }

    @GetMapping("/transactions")
    public ResponseEntity<List<TransactionResponse>> getAllTransactions() {
        return ResponseEntity.ok(transactionService.getAllTransactions());
    }

    @PutMapping("/accounts/{accountNumber}/status")
    public ResponseEntity<AccountResponse> updateAccountStatus(
            @PathVariable String accountNumber,
            @RequestParam String status) {
        Account account = accountRepository.findByAccountNumber(accountNumber)
                .orElseThrow(() -> new ResourceNotFoundException("Account not found with number: " + accountNumber));

        account.setStatus(AccountStatus.valueOf(status.toUpperCase()));
        Account saved = accountRepository.save(account);
        return ResponseEntity.ok(accountService.convertToResponse(saved));
    }

    @PostMapping("/users/{username}/role")
    public ResponseEntity<User> updateUserRole(
            @PathVariable String username,
            @RequestParam String role) {
        userService.updateUserRole(username, com.project.onlinebankingsystem.enums.Role.valueOf(role.toUpperCase()));
        User updated = userService.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + username));
        return ResponseEntity.ok(updated);
    }

    @PostMapping("/users/{username}/promote")
    public ResponseEntity<User> promoteToAdmin(@PathVariable String username) {
        User updated = userService.promoteToAdmin(username);
        return ResponseEntity.ok(updated);
    }
}
