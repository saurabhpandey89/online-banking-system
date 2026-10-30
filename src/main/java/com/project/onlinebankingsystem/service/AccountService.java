package com.project.onlinebankingsystem.service;

import com.project.onlinebankingsystem.dto.AccountRequest;
import com.project.onlinebankingsystem.dto.AccountResponse;
import com.project.onlinebankingsystem.enums.AccountStatus;
import com.project.onlinebankingsystem.enums.AccountType;
import com.project.onlinebankingsystem.exception.ResourceNotFoundException;
import com.project.onlinebankingsystem.model.Account;
import com.project.onlinebankingsystem.model.User;
import com.project.onlinebankingsystem.repository.AccountRepository;
import com.project.onlinebankingsystem.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;

import org.springframework.security.crypto.password.PasswordEncoder;

@Service
public class AccountService {

    private final AccountRepository accountRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;
    private final NotificationService notificationService;

    public AccountService(
            AccountRepository accountRepository,
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            AuditService auditService,
            NotificationService notificationService
    ) {
        this.accountRepository = accountRepository;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.auditService = auditService;
        this.notificationService = notificationService;
    }

    @Transactional
    public AccountResponse createAccount(
            String username,
            AccountRequest request
    ) {

        User user = userRepository.findByUsername(username)
                .orElseThrow(() ->
                        new ResourceNotFoundException(
                                "User not found with username: " + username
                        )
                );

        String accountNumber =
                generateUniqueAccountNumber();

        String upiId =
                generateUniqueUpiId(user.getUsername());

        Account account = Account.builder()
                .accountNumber(accountNumber)
                .upiId(upiId)
                .ifscCode("OBIN0000101")
                .accountType(
                        AccountType.valueOf(
                                request.getAccountType().toUpperCase()
                        )
                )
                .balance(BigDecimal.ZERO)
                .status(AccountStatus.ACTIVE)
                .createdAt(LocalDateTime.now())
                .user(user)
                .build();

        Account saved =
                accountRepository.save(account);

        return convertToResponse(saved);
    }


    // ===============================
    // UNIQUE UPI ID
    // ===============================

    private String generateUniqueUpiId(String username) {

        String base = username
                .toLowerCase()
                .replaceAll("[^a-z0-9]", "");

        if (base.isBlank()) {
            base = "user";
        }

        String upiId = base + "@obsbank";

        int counter = 1;

        while (
                accountRepository
                        .findByUpiId(upiId)
                        .isPresent()
        ) {

            upiId = base + counter + "@obsbank";

            counter++;
        }

        return upiId;
    }


    // ===============================
    // GET ACCOUNT DETAILS
    // ===============================

    public AccountResponse getAccountDetails(
            String accountNumber
    ) {

        Account account =
                accountRepository
                        .findByAccountNumber(accountNumber)
                        .orElseThrow(() ->
                                new ResourceNotFoundException(
                                        "Account not found with number: "
                                                + accountNumber
                                )
                        );

        return convertToResponse(account);
    }


    // ===============================
    // GET ACCOUNTS BY USERNAME
    // ===============================

    @Transactional
    public List<AccountResponse> getAccountsByUsername(
            String username
    ) {

        List<Account> accounts =
                accountRepository.findByUserUsername(username);

        boolean updated = false;

        for (Account account : accounts) {

            if (account.getUpiId() == null ||
                    account.getUpiId().isBlank()) {

                String upiId =
                        generateUniqueUpiId(username);

                account.setUpiId(upiId);

                updated = true;
            }
        }

        if (updated) {
            accountRepository.saveAll(accounts);
        }

        return accounts.stream()
                .map(this::convertToResponse)
                .collect(Collectors.toList());
    }


    // ===============================
    // GET ALL ACCOUNTS
    // ===============================

    public List<AccountResponse> getAllAccounts() {

        return accountRepository.findAll()
                .stream()
                .map(this::convertToResponse)
                .collect(Collectors.toList());
    }


    // ===============================
    // CONVERT TO RESPONSE
    // ===============================

    public AccountResponse convertToResponse(
            Account account
    ) {

        return AccountResponse.builder()
                .id(account.getId())
                .accountNumber(account.getAccountNumber())
                .upiId(account.getUpiId())
                .ifscCode(account.getIfscCode())
                .accountType(account.getAccountType().name())
                .balance(account.getBalance())
                .status(account.getStatus().name())
                .createdAt(account.getCreatedAt())
                .userUsername(
                        account.getUser() != null
                                ? account.getUser().getUsername()
                                : null
                )
                .userFullName(
                        account.getUser() != null
                                ? account.getUser().getFullName()
                                : null
                )
                .userEmail(
                        account.getUser() != null
                                ? account.getUser().getEmail()
                                : null
                )
                .perTransactionLimit(account.getPerTransactionLimit() != null ? account.getPerTransactionLimit() : new BigDecimal("50000.00"))
                .dailyLimit(account.getDailyLimit() != null ? account.getDailyLimit() : new BigDecimal("100000.00"))
                .hasTransactionPin(account.getTransactionPin() != null && !account.getTransactionPin().isBlank())
                .build();
    }

    @Transactional
    public AccountResponse freezeAccount(String accountNumber, String username) {
        Account account = accountRepository.findByAccountNumber(accountNumber)
                .orElseThrow(() -> new ResourceNotFoundException("Account not found: " + accountNumber));
        if (username != null && account.getUser() != null && !account.getUser().getUsername().equals(username)) {
            throw new SecurityException("Unauthorized access to account: " + accountNumber);
        }
        account.setStatus(AccountStatus.FROZEN);
        Account saved = accountRepository.save(account);
        auditService.log(username, "FREEZE_ACCOUNT", "Account " + accountNumber + " has been FROZEN", (String) null, "SUCCESS");
        notificationService.notifySecurity(account.getUser(), "Account Frozen", "Your account " + accountNumber + " has been temporarily frozen. Debit transactions are suspended.");
        return convertToResponse(saved);
    }

    @Transactional
    public AccountResponse unfreezeAccount(String accountNumber, String username) {
        Account account = accountRepository.findByAccountNumber(accountNumber)
                .orElseThrow(() -> new ResourceNotFoundException("Account not found: " + accountNumber));
        if (username != null && account.getUser() != null && !account.getUser().getUsername().equals(username)) {
            throw new SecurityException("Unauthorized access to account: " + accountNumber);
        }
        account.setStatus(AccountStatus.ACTIVE);
        Account saved = accountRepository.save(account);
        auditService.log(username, "UNFREEZE_ACCOUNT", "Account " + accountNumber + " has been UNFROZEN to ACTIVE", (String) null, "SUCCESS");
        notificationService.notifySecurity(account.getUser(), "Account Unfrozen", "Your account " + accountNumber + " has been activated. Normal transactions resumed.");
        return convertToResponse(saved);
    }

    @Transactional
    public AccountResponse closeAccount(String accountNumber, String username, String reason) {
        Account account = accountRepository.findByAccountNumber(accountNumber)
                .orElseThrow(() -> new ResourceNotFoundException("Account not found: " + accountNumber));
        if (username != null && account.getUser() != null && !account.getUser().getUsername().equals(username) && !username.equalsIgnoreCase("ADMIN")) {
            throw new SecurityException("Unauthorized access to account: " + accountNumber);
        }
        if (account.getBalance() != null && account.getBalance().compareTo(BigDecimal.ZERO) > 0) {
            throw new IllegalStateException("Account balance must be zero before closing. Current balance: ₹" + account.getBalance());
        }
        account.setStatus(AccountStatus.CLOSED);
        Account saved = accountRepository.save(account);
        String note = reason != null && !reason.isBlank() ? " Reason: " + reason : "";
        auditService.log(username != null ? username : "SYSTEM", "CLOSE_ACCOUNT", "Account " + accountNumber + " closed permanently (Soft-delete)." + note, (String) null, "SUCCESS");
        notificationService.notifySecurity(account.getUser(), "Account Closed", "Your account " + accountNumber + " has been closed. Transaction history remains preserved for compliance.");
        return convertToResponse(saved);
    }

    @Transactional
    public AccountResponse suspendAccount(String accountNumber, String username, String reason) {
        Account account = accountRepository.findByAccountNumber(accountNumber)
                .orElseThrow(() -> new ResourceNotFoundException("Account not found: " + accountNumber));
        if (username != null && account.getUser() != null && !account.getUser().getUsername().equals(username) && !username.equalsIgnoreCase("ADMIN")) {
            throw new SecurityException("Unauthorized access to account: " + accountNumber);
        }
        account.setStatus(AccountStatus.SUSPENDED);
        Account saved = accountRepository.save(account);
        String note = reason != null && !reason.isBlank() ? " Reason: " + reason : "";
        auditService.log(username != null ? username : "SYSTEM", "SUSPEND_ACCOUNT", "Account " + accountNumber + " status set to SUSPENDED." + note, (String) null, "SUCCESS");
        notificationService.notifySecurity(account.getUser(), "Account Suspended", "Your account " + accountNumber + " has been suspended.");
        return convertToResponse(saved);
    }

    @Transactional
    public void setTransactionPin(String accountNumber, String rawPin, String username) {
        Account account = accountRepository.findByAccountNumber(accountNumber)
                .orElseThrow(() -> new ResourceNotFoundException("Account not found: " + accountNumber));
        if (username != null && account.getUser() != null && !account.getUser().getUsername().equals(username)) {
            throw new SecurityException("Unauthorized access to account: " + accountNumber);
        }
        if (rawPin == null || !rawPin.matches("^\\d{4,6}$")) {
            throw new IllegalArgumentException("Transaction PIN must be 4 to 6 numeric digits.");
        }
        account.setTransactionPin(passwordEncoder.encode(rawPin));
        accountRepository.save(account);
        auditService.log(username, "SET_TRANSACTION_PIN", "Transaction PIN updated for account " + accountNumber, (String) null, "SUCCESS");
        notificationService.notifySecurity(account.getUser(), "Transaction PIN Updated", "Transaction MPIN for account " + accountNumber + " was successfully configured.");
    }

    @Transactional
    public void updateLimits(String accountNumber, BigDecimal perTxLimit, BigDecimal dailyLimit, String username) {
        Account account = accountRepository.findByAccountNumber(accountNumber)
                .orElseThrow(() -> new ResourceNotFoundException("Account not found: " + accountNumber));
        if (username != null && account.getUser() != null && !account.getUser().getUsername().equals(username)) {
            throw new SecurityException("Unauthorized access to account: " + accountNumber);
        }
        if (perTxLimit != null && perTxLimit.compareTo(BigDecimal.ZERO) > 0) {
            account.setPerTransactionLimit(perTxLimit);
        }
        if (dailyLimit != null && dailyLimit.compareTo(BigDecimal.ZERO) > 0) {
            account.setDailyLimit(dailyLimit);
        }
        accountRepository.save(account);
        auditService.log(username, "UPDATE_LIMITS", "Updated limits for " + accountNumber + " to Per-Tx: " + perTxLimit + ", Daily: " + dailyLimit, (String) null, "SUCCESS");
        notificationService.notifySecurity(account.getUser(), "Transaction Limits Updated", "Daily limit set to ₹" + dailyLimit + ", per-transaction limit set to ₹" + perTxLimit);
    }


    // ===============================
    // UNIQUE ACCOUNT NUMBER
    // ===============================

    private String generateUniqueAccountNumber() {

        Random random = new Random();

        String accountNumber;

        do {

            long num =
                    1000000000L
                            + (long) (
                            random.nextDouble()
                                    * 9000000000L
                    );

            accountNumber =
                    String.valueOf(num);

        } while (
                accountRepository
                        .existsByAccountNumber(accountNumber)
        );

        return accountNumber;
    }
}