package com.project.onlinebankingsystem.service;

import com.project.onlinebankingsystem.dto.TransactionResponse;
import com.project.onlinebankingsystem.enums.AccountStatus;
import com.project.onlinebankingsystem.enums.TransactionStatus;
import com.project.onlinebankingsystem.enums.TransactionType;
import com.project.onlinebankingsystem.exception.AccountSuspendedException;
import com.project.onlinebankingsystem.exception.InsufficientBalanceException;
import com.project.onlinebankingsystem.exception.ResourceNotFoundException;
import com.project.onlinebankingsystem.model.Account;
import com.project.onlinebankingsystem.model.Transaction;
import com.project.onlinebankingsystem.repository.AccountRepository;
import com.project.onlinebankingsystem.repository.TransactionRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class TransactionService {

    // Commercial Banking Limits (Standard Indian Retail Banking Thresholds)
    public static final BigDecimal MIN_TRANSACTION_AMOUNT = new BigDecimal("1.00");
    public static final BigDecimal MAX_TRANSACTION_LIMIT = new BigDecimal("1000000.00"); // ₹10,00,000.00 System Ceiling
    public static final BigDecimal MAX_DEPOSIT_LIMIT = new BigDecimal("1000000.00");     // ₹10,00,000.00 Max Per Deposit

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;
    private final NotificationService notificationService;
    private final IdempotencyService idempotencyService;

    public TransactionService(AccountRepository accountRepository,
                              TransactionRepository transactionRepository,
                              PasswordEncoder passwordEncoder,
                              AuditService auditService,
                              NotificationService notificationService,
                              IdempotencyService idempotencyService) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.passwordEncoder = passwordEncoder;
        this.auditService = auditService;
        this.notificationService = notificationService;
        this.idempotencyService = idempotencyService;
    }

    /**
     * Validates and normalizes transaction amount according to real commercial banking standards:
     * 1. amount != null
     * 2. amount > 0 (Strictly positive)
     * 3. amount >= MIN_TRANSACTION_AMOUNT (₹1.00)
     * 4. Enforces decimal/scale validation (maximum 2 decimal places / paise; rejects fractional paise)
     * 5. Checks against bank system ceiling limit
     */
    public BigDecimal validateAndNormalizeAmount(BigDecimal amount) {
        return validateAndNormalizeAmount(amount, MAX_TRANSACTION_LIMIT);
    }

    public BigDecimal validateAndNormalizeAmount(BigDecimal amount, BigDecimal ceilingLimit) {
        // 1. Null check
        if (amount == null) {
            throw new IllegalArgumentException("Transaction amount is required and cannot be empty.");
        }

        // 2. Strictly positive check (> 0)
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Transaction amount must be strictly greater than zero (₹0.00).");
        }

        // 3. Minimum transaction threshold check (₹1.00)
        if (amount.compareTo(MIN_TRANSACTION_AMOUNT) < 0) {
            throw new IllegalArgumentException("Minimum transaction amount allowed is ₹" + formatAmount(MIN_TRANSACTION_AMOUNT) + ".");
        }

        // 4. Decimal/scale validation: Reject fractions of a paisa (e.g. 10.555 or 0.001)
        if (amount.scale() > 2 && amount.stripTrailingZeros().scale() > 2) {
            throw new IllegalArgumentException("Invalid amount format. Transaction amount cannot exceed 2 decimal places (paise). Example: ₹500.50");
        }

        BigDecimal normalized = amount.setScale(2, RoundingMode.HALF_UP);

        // 5. System Ceiling / Maximum transaction limit check
        BigDecimal maxAllowed = ceilingLimit != null ? ceilingLimit : MAX_TRANSACTION_LIMIT;
        if (normalized.compareTo(maxAllowed) > 0) {
            throw new IllegalArgumentException("Transaction amount (₹" + formatAmount(normalized) + ") exceeds the maximum permissible limit of ₹" + formatAmount(maxAllowed) + ".");
        }

        return normalized;
    }

    public String formatAmount(BigDecimal amount) {
        if (amount == null) return "0.00";
        try {
            NumberFormat nf = NumberFormat.getNumberInstance(new Locale("en", "IN"));
            nf.setMinimumFractionDigits(2);
            nf.setMaximumFractionDigits(2);
            return nf.format(amount);
        } catch (Exception e) {
            return amount.setScale(2, RoundingMode.HALF_UP).toString();
        }
    }

    @Transactional
    public TransactionResponse deposit(String accountNumber, BigDecimal amount, String description) {
        return deposit(accountNumber, amount, description, null);
    }

    @Transactional
    public TransactionResponse deposit(String accountNumber, BigDecimal amount, String description, String idempotencyKey) {
        if (accountNumber == null || accountNumber.isBlank()) {
            throw new IllegalArgumentException("Account number is required.");
        }

        BigDecimal normalizedAmount = validateAndNormalizeAmount(amount, MAX_DEPOSIT_LIMIT);

        // Idempotency check & lock
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            String hash = idempotencyService.computeRequestHash("DEPOSIT", null, accountNumber, normalizedAmount);
            TransactionResponse cached = idempotencyService.checkIdempotency(idempotencyKey, "DEPOSIT", hash);
            if (cached != null) {
                return cached;
            }
            idempotencyService.createProcessingRecord(idempotencyKey, "DEPOSIT", hash);
        }

        try {
            Account account = accountRepository.findByAccountNumberWithLock(accountNumber)
                    .orElseThrow(() -> new ResourceNotFoundException("Account not found with number: " + accountNumber));

            if (account.getStatus() != AccountStatus.ACTIVE) {
                saveFailedTransaction(null, account, normalizedAmount, TransactionType.DEPOSIT, "Account is " + account.getStatus(), description);
                throw new AccountSuspendedException("Account is " + account.getStatus() + ". Cannot perform transaction.");
            }

            account.setBalance(account.getBalance().add(normalizedAmount));
            accountRepository.save(account);

            String ref = UUID.randomUUID().toString();
            Transaction transaction = Transaction.builder()
                    .transactionReference(ref)
                    .idempotencyKey(idempotencyKey != null && !idempotencyKey.isBlank() ? idempotencyKey.trim() : null)
                    .destinationAccount(account)
                    .amount(normalizedAmount)
                    .type(TransactionType.DEPOSIT)
                    .status(TransactionStatus.SUCCESS)
                    .completedAt(LocalDateTime.now())
                    .description(description != null && !description.isBlank() ? description : "Cash Deposit")
                    .timestamp(LocalDateTime.now())
                    .build();

            Transaction saved = transactionRepository.save(transaction);
            notificationService.notifyTransaction(account.getUser(), "DEPOSIT", normalizedAmount, account.getAccountNumber(), ref);
            auditService.log(account.getUser() != null ? account.getUser().getUsername() : "SYSTEM", "DEPOSIT", "Deposited ₹" + formatAmount(normalizedAmount) + " to A/c " + accountNumber, (String) null, "SUCCESS");

            TransactionResponse response = convertToResponse(saved);
            if (idempotencyKey != null && !idempotencyKey.isBlank()) {
                idempotencyService.markCompleted(idempotencyKey, response);
            }
            return response;
        } catch (Exception ex) {
            if (idempotencyKey != null && !idempotencyKey.isBlank()) {
                idempotencyService.markFailed(idempotencyKey);
            }
            throw ex;
        }
    }

    @Transactional
    public TransactionResponse withdraw(String accountNumber, BigDecimal amount, String description) {
        return withdraw(accountNumber, amount, description, null, null);
    }

    @Transactional
    public TransactionResponse withdraw(String accountNumber, BigDecimal amount, String description, String pin) {
        return withdraw(accountNumber, amount, description, pin, null);
    }

    @Transactional
    public TransactionResponse withdraw(String accountNumber, BigDecimal amount, String description, String pin, String idempotencyKey) {
        if (accountNumber == null || accountNumber.isBlank()) {
            throw new IllegalArgumentException("Account number is required.");
        }

        BigDecimal normalizedAmount = validateAndNormalizeAmount(amount, MAX_TRANSACTION_LIMIT);

        // Idempotency check & lock
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            String hash = idempotencyService.computeRequestHash("WITHDRAWAL", accountNumber, null, normalizedAmount);
            TransactionResponse cached = idempotencyService.checkIdempotency(idempotencyKey, "WITHDRAWAL", hash);
            if (cached != null) {
                return cached;
            }
            idempotencyService.createProcessingRecord(idempotencyKey, "WITHDRAWAL", hash);
        }

        try {
            Account account = accountRepository.findByAccountNumberWithLock(accountNumber)
                    .orElseThrow(() -> new ResourceNotFoundException("Account not found with number: " + accountNumber));

            String username = account.getUser() != null ? account.getUser().getUsername() : "SYSTEM";

            // 1. Account freeze & active status check
            if (account.getStatus() == AccountStatus.FROZEN) {
                saveFailedTransaction(account, null, normalizedAmount, TransactionType.WITHDRAWAL, "Account is Temporarily Frozen", description);
                auditService.log(username, "FAILED_WITHDRAWAL", "Attempted withdrawal on FROZEN account " + accountNumber, (String) null, "FAILED");
                throw new AccountSuspendedException("Account is FROZEN. Please unfreeze your account to perform withdrawals.");
            } else if (account.getStatus() != AccountStatus.ACTIVE) {
                saveFailedTransaction(account, null, normalizedAmount, TransactionType.WITHDRAWAL, "Account is " + account.getStatus(), description);
                throw new AccountSuspendedException("Account is " + account.getStatus() + ". Cannot perform transaction.");
            }

            // 2. Transaction PIN validation
            if (account.getTransactionPin() != null && !account.getTransactionPin().isBlank()) {
                if (pin == null || !passwordEncoder.matches(pin, account.getTransactionPin())) {
                    saveFailedTransaction(account, null, normalizedAmount, TransactionType.WITHDRAWAL, "Invalid Transaction PIN", description);
                    auditService.log(username, "FAILED_WITHDRAWAL", "Invalid TPIN entered for " + accountNumber, (String) null, "FAILED");
                    throw new SecurityException("Invalid 4-digit Transaction PIN. Please check your PIN and try again.");
                }
            }

            // 3. Per-transaction limit check
            if (account.getPerTransactionLimit() != null && normalizedAmount.compareTo(account.getPerTransactionLimit()) > 0) {
                String reason = "Transaction amount (₹" + formatAmount(normalizedAmount) + ") exceeds account per-transaction limit of ₹" + formatAmount(account.getPerTransactionLimit()) + ".";
                saveFailedTransaction(account, null, normalizedAmount, TransactionType.WITHDRAWAL, reason, description);
                auditService.log(username, "FAILED_WITHDRAWAL", reason + " for " + accountNumber, (String) null, "FAILED");
                throw new IllegalArgumentException(reason);
            }

            // 4. Daily transaction limit check
            BigDecimal dailySpent = getDailyDebits(account);
            if (account.getDailyLimit() != null && dailySpent.add(normalizedAmount).compareTo(account.getDailyLimit()) > 0) {
                BigDecimal remainingLimit = account.getDailyLimit().subtract(dailySpent).max(BigDecimal.ZERO);
                String reason = "Transaction exceeds account daily limit of ₹" + formatAmount(account.getDailyLimit()) + 
                                " (Already spent today: ₹" + formatAmount(dailySpent) + ", Available daily limit remaining: ₹" + formatAmount(remainingLimit) + ").";
                saveFailedTransaction(account, null, normalizedAmount, TransactionType.WITHDRAWAL, reason, description);
                auditService.log(username, "FAILED_WITHDRAWAL", reason + " for " + accountNumber, (String) null, "FAILED");
                throw new IllegalArgumentException(reason);
            }

            // 5. Sufficient Balance check
            if (account.getBalance().compareTo(normalizedAmount) < 0) {
                String reason = "Insufficient balance in account " + accountNumber + ". Available balance: ₹" + formatAmount(account.getBalance()) + ", Requested withdrawal: ₹" + formatAmount(normalizedAmount) + ".";
                saveFailedTransaction(account, null, normalizedAmount, TransactionType.WITHDRAWAL, "Insufficient Balance", description);
                auditService.log(username, "FAILED_WITHDRAWAL", reason, (String) null, "FAILED");
                throw new InsufficientBalanceException(reason);
            }

            account.setBalance(account.getBalance().subtract(normalizedAmount));
            accountRepository.save(account);

            String ref = UUID.randomUUID().toString();
            Transaction transaction = Transaction.builder()
                    .transactionReference(ref)
                    .idempotencyKey(idempotencyKey != null && !idempotencyKey.isBlank() ? idempotencyKey.trim() : null)
                    .sourceAccount(account)
                    .amount(normalizedAmount)
                    .type(TransactionType.WITHDRAWAL)
                    .status(TransactionStatus.SUCCESS)
                    .completedAt(LocalDateTime.now())
                    .description(description != null && !description.isBlank() ? description : "Cash Withdrawal")
                    .timestamp(LocalDateTime.now())
                    .build();

            Transaction saved = transactionRepository.save(transaction);
            notificationService.notifyTransaction(account.getUser(), "WITHDRAWAL", normalizedAmount, account.getAccountNumber(), ref);
            auditService.log(username, "WITHDRAWAL", "Withdrew ₹" + formatAmount(normalizedAmount) + " from A/c " + accountNumber, (String) null, "SUCCESS");

            TransactionResponse response = convertToResponse(saved);
            if (idempotencyKey != null && !idempotencyKey.isBlank()) {
                idempotencyService.markCompleted(idempotencyKey, response);
            }
            return response;
        } catch (Exception ex) {
            if (idempotencyKey != null && !idempotencyKey.isBlank()) {
                idempotencyService.markFailed(idempotencyKey);
            }
            throw ex;
        }
    }

    @Transactional
    public TransactionResponse transfer(String sourceAccountNumber, String destinationAccountNumber, BigDecimal amount, String description) {
        return transfer(sourceAccountNumber, destinationAccountNumber, amount, description, null, null);
    }

    @Transactional
    public TransactionResponse transfer(String sourceAccountNumber, String destinationAccountNumber, BigDecimal amount, String description, String pin) {
        return transfer(sourceAccountNumber, destinationAccountNumber, amount, description, pin, null);
    }

    @Transactional
    public TransactionResponse transfer(String sourceAccountNumber, String destinationAccountNumber, BigDecimal amount, String description, String pin, String idempotencyKey) {
        if (sourceAccountNumber == null || sourceAccountNumber.isBlank()) {
            throw new IllegalArgumentException("Source account number is required.");
        }
        if (destinationAccountNumber == null || destinationAccountNumber.isBlank()) {
            throw new IllegalArgumentException("Destination account number is required.");
        }
        if (sourceAccountNumber.trim().equalsIgnoreCase(destinationAccountNumber.trim())) {
            throw new IllegalArgumentException("Source and destination account numbers must be different.");
        }

        BigDecimal normalizedAmount = validateAndNormalizeAmount(amount, MAX_TRANSACTION_LIMIT);

        // Idempotency check & lock
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            String hash = idempotencyService.computeRequestHash("TRANSFER", sourceAccountNumber, destinationAccountNumber, normalizedAmount);
            TransactionResponse cached = idempotencyService.checkIdempotency(idempotencyKey, "TRANSFER", hash);
            if (cached != null) {
                return cached;
            }
            idempotencyService.createProcessingRecord(idempotencyKey, "TRANSFER", hash);
        }

        try {
            Account source;
            Account destination;

            // Deadlock prevention logic: lock in lexicographical order of account numbers
            if (sourceAccountNumber.compareTo(destinationAccountNumber) < 0) {
                source = accountRepository.findByAccountNumberWithLock(sourceAccountNumber)
                        .orElseThrow(() -> new ResourceNotFoundException("Source account not found: " + sourceAccountNumber));
                destination = accountRepository.findByAccountNumberWithLock(destinationAccountNumber)
                        .orElseThrow(() -> new ResourceNotFoundException("Destination account not found: " + destinationAccountNumber));
            } else {
                destination = accountRepository.findByAccountNumberWithLock(destinationAccountNumber)
                        .orElseThrow(() -> new ResourceNotFoundException("Destination account not found: " + destinationAccountNumber));
                source = accountRepository.findByAccountNumberWithLock(sourceAccountNumber)
                        .orElseThrow(() -> new ResourceNotFoundException("Source account not found: " + sourceAccountNumber));
            }

            String username = source.getUser() != null ? source.getUser().getUsername() : "SYSTEM";

            // 1. Account freeze & status check
            if (source.getStatus() == AccountStatus.FROZEN) {
                saveFailedTransaction(source, destination, normalizedAmount, TransactionType.TRANSFER, "Source Account is Temporarily Frozen", description);
                auditService.log(username, "FAILED_TRANSFER", "Attempted transfer from FROZEN account " + sourceAccountNumber, (String) null, "FAILED");
                throw new AccountSuspendedException("Your account is FROZEN. Please unfreeze your account to perform transfers.");
            } else if (source.getStatus() != AccountStatus.ACTIVE) {
                saveFailedTransaction(source, destination, normalizedAmount, TransactionType.TRANSFER, "Source account is " + source.getStatus(), description);
                throw new AccountSuspendedException("Source account is " + source.getStatus() + ". Cannot initiate transfer.");
            }

            if (destination.getStatus() != AccountStatus.ACTIVE && destination.getStatus() != AccountStatus.FROZEN) {
                saveFailedTransaction(source, destination, normalizedAmount, TransactionType.TRANSFER, "Destination account is " + destination.getStatus(), description);
                throw new AccountSuspendedException("Destination account is " + destination.getStatus() + ". Cannot receive funds.");
            }

            // 2. Transaction PIN validation
            if (source.getTransactionPin() != null && !source.getTransactionPin().isBlank()) {
                if (pin == null || !passwordEncoder.matches(pin, source.getTransactionPin())) {
                    saveFailedTransaction(source, destination, normalizedAmount, TransactionType.TRANSFER, "Invalid Transaction PIN", description);
                    auditService.log(username, "FAILED_TRANSFER", "Invalid TPIN entered for " + sourceAccountNumber, (String) null, "FAILED");
                    throw new SecurityException("Invalid 4-digit Transaction PIN. Please check your PIN and try again.");
                }
            }

            // 3. Per-transaction limit check
            if (source.getPerTransactionLimit() != null && normalizedAmount.compareTo(source.getPerTransactionLimit()) > 0) {
                String reason = "Transfer amount (₹" + formatAmount(normalizedAmount) + ") exceeds account per-transaction limit of ₹" + formatAmount(source.getPerTransactionLimit()) + ".";
                saveFailedTransaction(source, destination, normalizedAmount, TransactionType.TRANSFER, reason, description);
                auditService.log(username, "FAILED_TRANSFER", reason + " for " + sourceAccountNumber, (String) null, "FAILED");
                throw new IllegalArgumentException(reason);
            }

            // 4. Daily transaction limit check
            BigDecimal dailySpent = getDailyDebits(source);
            if (source.getDailyLimit() != null && dailySpent.add(normalizedAmount).compareTo(source.getDailyLimit()) > 0) {
                BigDecimal remainingLimit = source.getDailyLimit().subtract(dailySpent).max(BigDecimal.ZERO);
                String reason = "Transfer exceeds account daily limit of ₹" + formatAmount(source.getDailyLimit()) + 
                                " (Already spent today: ₹" + formatAmount(dailySpent) + ", Available daily limit remaining: ₹" + formatAmount(remainingLimit) + ").";
                saveFailedTransaction(source, destination, normalizedAmount, TransactionType.TRANSFER, reason, description);
                auditService.log(username, "FAILED_TRANSFER", reason + " for " + sourceAccountNumber, (String) null, "FAILED");
                throw new IllegalArgumentException(reason);
            }

            // 5. Sufficient Balance check
            if (source.getBalance().compareTo(normalizedAmount) < 0) {
                String reason = "Insufficient balance in source account " + sourceAccountNumber + ". Available balance: ₹" + formatAmount(source.getBalance()) + ", Requested transfer: ₹" + formatAmount(normalizedAmount) + ".";
                saveFailedTransaction(source, destination, normalizedAmount, TransactionType.TRANSFER, "Insufficient Balance", description);
                auditService.log(username, "FAILED_TRANSFER", reason, (String) null, "FAILED");
                throw new InsufficientBalanceException(reason);
            }

            source.setBalance(source.getBalance().subtract(normalizedAmount));
            destination.setBalance(destination.getBalance().add(normalizedAmount));

            accountRepository.save(source);
            accountRepository.save(destination);

            String ref = UUID.randomUUID().toString();
            Transaction transaction = Transaction.builder()
                    .transactionReference(ref)
                    .idempotencyKey(idempotencyKey != null && !idempotencyKey.isBlank() ? idempotencyKey.trim() : null)
                    .sourceAccount(source)
                    .destinationAccount(destination)
                    .amount(normalizedAmount)
                    .type(TransactionType.TRANSFER)
                    .status(TransactionStatus.SUCCESS)
                    .completedAt(LocalDateTime.now())
                    .description(description != null && !description.isBlank() ? description : "Fund Transfer")
                    .timestamp(LocalDateTime.now())
                    .build();

            Transaction saved = transactionRepository.save(transaction);

            // Send simulated SMS/Email notifications
            notificationService.notifyTransaction(source.getUser(), "TRANSFER", normalizedAmount, source.getAccountNumber(), ref);
            notificationService.notifyTransaction(destination.getUser(), "CREDIT", normalizedAmount, destination.getAccountNumber(), ref);
            auditService.log(username, "TRANSFER", "Transferred ₹" + formatAmount(normalizedAmount) + " from " + sourceAccountNumber + " to " + destinationAccountNumber, (String) null, "SUCCESS");

            TransactionResponse response = convertToResponse(saved);
            if (idempotencyKey != null && !idempotencyKey.isBlank()) {
                idempotencyService.markCompleted(idempotencyKey, response);
            }
            return response;
        } catch (Exception ex) {
            if (idempotencyKey != null && !idempotencyKey.isBlank()) {
                idempotencyService.markFailed(idempotencyKey);
            }
            throw ex;
        }
    }

    @Transactional
    public TransactionResponse reverseTransaction(String transactionReference, String adminUsername) {
        Transaction tx = transactionRepository.findByTransactionReference(transactionReference)
                .orElseThrow(() -> new ResourceNotFoundException("Transaction not found: " + transactionReference));

        if (tx.getStatus() != TransactionStatus.SUCCESS) {
            throw new IllegalStateException("Only successful transactions can be reversed.");
        }
        if (tx.getReversalReference() != null && !tx.getReversalReference().isBlank()) {
            throw new IllegalStateException("Transaction has already been reversed.");
        }

        Account source = tx.getSourceAccount();
        Account destination = tx.getDestinationAccount();
        BigDecimal amount = tx.getAmount();

        if ((tx.getType() == TransactionType.TRANSFER || tx.getType() == TransactionType.UPI_PAYMENT) && source != null && destination != null) {
            if (destination.getBalance().compareTo(amount) < 0) {
                throw new InsufficientBalanceException("Cannot reverse: Recipient account has insufficient balance (₹" + destination.getBalance() + ").");
            }
            destination.setBalance(destination.getBalance().subtract(amount));
            source.setBalance(source.getBalance().add(amount));
            accountRepository.save(destination);
            accountRepository.save(source);
        } else if ((tx.getType() == TransactionType.WITHDRAWAL || tx.getType() == TransactionType.UPI_PAYMENT) && source != null) {
            source.setBalance(source.getBalance().add(amount));
            accountRepository.save(source);
        } else if (tx.getType() == TransactionType.DEPOSIT && destination != null) {
            if (destination.getBalance().compareTo(amount) < 0) {
                throw new InsufficientBalanceException("Cannot reverse: Account has insufficient balance to reverse deposit.");
            }
            destination.setBalance(destination.getBalance().subtract(amount));
            accountRepository.save(destination);
        }

        String revRef = "REV-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        tx.setStatus(TransactionStatus.REVERSED);
        tx.setReversalReference(revRef);
        tx.setCompletedAt(LocalDateTime.now());
        Transaction saved = transactionRepository.save(tx);

        if (source != null) {
            notificationService.notifySecurity(source.getUser(), "Transaction Reversed / Refunded", "Transaction of ₹" + amount + " (Ref: " + transactionReference + ") was refunded back to your account.");
        }
        if (destination != null) {
            notificationService.notifySecurity(destination.getUser(), "Transaction Reversed", "Transaction of ₹" + amount + " (Ref: " + transactionReference + ") has been reversed.");
        }

        auditService.log(adminUsername, "REVERSE_TRANSACTION", "Reversed tx " + transactionReference + " for ₹" + amount, (String) null, "SUCCESS");
        return convertToResponse(saved);
    }

    @Transactional
    public TransactionResponse initiatePendingTransfer(String sourceAccountNumber, String destinationAccountNumber, BigDecimal amount, String description, String pin) {
        if (sourceAccountNumber == null || sourceAccountNumber.isBlank()) {
            throw new IllegalArgumentException("Source account number is required.");
        }
        if (destinationAccountNumber == null || destinationAccountNumber.isBlank()) {
            throw new IllegalArgumentException("Destination account number is required.");
        }
        if (sourceAccountNumber.trim().equalsIgnoreCase(destinationAccountNumber.trim())) {
            throw new IllegalArgumentException("Source and destination account numbers must be different.");
        }

        BigDecimal normalizedAmount = validateAndNormalizeAmount(amount, MAX_TRANSACTION_LIMIT);

        Account source = accountRepository.findByAccountNumber(sourceAccountNumber)
                .orElseThrow(() -> new ResourceNotFoundException("Source account not found: " + sourceAccountNumber));
        Account destination = accountRepository.findByAccountNumber(destinationAccountNumber)
                .orElseThrow(() -> new ResourceNotFoundException("Destination account not found: " + destinationAccountNumber));

        String username = source.getUser() != null ? source.getUser().getUsername() : "SYSTEM";

        if (source.getStatus() == AccountStatus.FROZEN) {
            throw new AccountSuspendedException("Your account is FROZEN. Cannot initiate transfer.");
        } else if (source.getStatus() != AccountStatus.ACTIVE) {
            throw new AccountSuspendedException("Source account is " + source.getStatus() + ". Cannot initiate transfer.");
        }

        if (destination.getStatus() != AccountStatus.ACTIVE && destination.getStatus() != AccountStatus.FROZEN) {
            throw new AccountSuspendedException("Destination account is " + destination.getStatus() + ". Cannot receive funds.");
        }

        if (source.getTransactionPin() != null && !source.getTransactionPin().isBlank()) {
            if (pin == null || !passwordEncoder.matches(pin, source.getTransactionPin())) {
                auditService.log(username, "FAILED_INITIATE_PENDING", "Invalid TPIN entered for " + sourceAccountNumber, (String) null, "FAILED");
                throw new SecurityException("Invalid 4-digit Transaction PIN. Please check your PIN and try again.");
            }
        }

        if (source.getPerTransactionLimit() != null && normalizedAmount.compareTo(source.getPerTransactionLimit()) > 0) {
            throw new IllegalArgumentException("Transfer amount (₹" + formatAmount(normalizedAmount) + ") exceeds account per-transaction limit of ₹" + formatAmount(source.getPerTransactionLimit()) + ".");
        }

        BigDecimal dailySpent = getDailyDebits(source);
        if (source.getDailyLimit() != null && dailySpent.add(normalizedAmount).compareTo(source.getDailyLimit()) > 0) {
            throw new IllegalArgumentException("Transfer exceeds account daily limit of ₹" + formatAmount(source.getDailyLimit()) + ".");
        }

        if (source.getBalance().compareTo(normalizedAmount) < 0) {
            throw new InsufficientBalanceException("Insufficient balance in source account: " + sourceAccountNumber);
        }

        String ref = "TXN-PND-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        Transaction transaction = Transaction.builder()
                .transactionReference(ref)
                .sourceAccount(source)
                .destinationAccount(destination)
                .amount(normalizedAmount)
                .type(TransactionType.TRANSFER)
                .status(TransactionStatus.PENDING)
                .description(description != null && !description.isBlank() ? description : "Scheduled / Pending Transfer")
                .timestamp(LocalDateTime.now())
                .build();

        Transaction saved = transactionRepository.save(transaction);
        notificationService.notifySecurity(source.getUser(), "Transfer Queued (PENDING)", "Your transfer of ₹" + formatAmount(normalizedAmount) + " to A/c " + destinationAccountNumber + " is PENDING clearance. Ref: " + ref);
        auditService.log(username, "INITIATE_PENDING_TRANSFER", "Queued pending transfer ₹" + formatAmount(normalizedAmount) + " to " + destinationAccountNumber + " (Ref: " + ref + ")", (String) null, "PENDING");

        return convertToResponse(saved);
    }

    @Transactional
    public TransactionResponse approveTransaction(String transactionReference, String actorUsername) {
        Transaction tx = transactionRepository.findByTransactionReference(transactionReference)
                .orElseThrow(() -> new ResourceNotFoundException("Transaction not found: " + transactionReference));

        if (tx.getStatus() != TransactionStatus.PENDING) {
            throw new IllegalStateException("Only PENDING transactions can be approved. Current status: " + tx.getStatus());
        }

        Account source = tx.getSourceAccount();
        Account destination = tx.getDestinationAccount();
        BigDecimal amount = tx.getAmount();

        final String srcAccNo = source != null ? source.getAccountNumber() : null;
        final String destAccNo = destination != null ? destination.getAccountNumber() : null;

        if (srcAccNo != null) {
            source = accountRepository.findByAccountNumberWithLock(srcAccNo)
                    .orElseThrow(() -> new ResourceNotFoundException("Source account not found: " + srcAccNo));
        }
        if (destAccNo != null) {
            destination = accountRepository.findByAccountNumberWithLock(destAccNo)
                    .orElseThrow(() -> new ResourceNotFoundException("Destination account not found: " + destAccNo));
        }

        if (source != null && source.getStatus() != AccountStatus.ACTIVE) {
            tx.setStatus(TransactionStatus.FAILED);
            tx.setFailureReason("Approval failed: Source account is " + source.getStatus());
            tx.setCompletedAt(LocalDateTime.now());
            transactionRepository.save(tx);
            throw new AccountSuspendedException("Source account is " + source.getStatus() + ". Cannot approve transaction.");
        }

        if (destination != null && destination.getStatus() != AccountStatus.ACTIVE && destination.getStatus() != AccountStatus.FROZEN) {
            tx.setStatus(TransactionStatus.FAILED);
            tx.setFailureReason("Approval failed: Destination account is " + destination.getStatus());
            tx.setCompletedAt(LocalDateTime.now());
            transactionRepository.save(tx);
            throw new AccountSuspendedException("Destination account is " + destination.getStatus() + ". Cannot receive funds.");
        }

        if (source != null && source.getBalance().compareTo(amount) < 0) {
            tx.setStatus(TransactionStatus.FAILED);
            tx.setFailureReason("Approval failed: Insufficient balance in source account");
            tx.setCompletedAt(LocalDateTime.now());
            transactionRepository.save(tx);
            throw new InsufficientBalanceException("Cannot approve: Insufficient balance in source account (₹" + source.getBalance() + ").");
        }

        // Execute balances
        if (tx.getType() == TransactionType.TRANSFER && source != null && destination != null) {
            source.setBalance(source.getBalance().subtract(amount));
            destination.setBalance(destination.getBalance().add(amount));
            accountRepository.save(source);
            accountRepository.save(destination);
        } else if (tx.getType() == TransactionType.WITHDRAWAL && source != null) {
            source.setBalance(source.getBalance().subtract(amount));
            accountRepository.save(source);
        } else if (tx.getType() == TransactionType.DEPOSIT && destination != null) {
            destination.setBalance(destination.getBalance().add(amount));
            accountRepository.save(destination);
        }

        tx.setStatus(TransactionStatus.SUCCESS);
        tx.setCompletedAt(LocalDateTime.now());
        Transaction saved = transactionRepository.save(tx);

        if (source != null) {
            notificationService.notifyTransaction(source.getUser(), tx.getType().name(), amount, source.getAccountNumber(), transactionReference);
        }
        if (destination != null && tx.getType() == TransactionType.TRANSFER) {
            notificationService.notifyTransaction(destination.getUser(), "CREDIT", amount, destination.getAccountNumber(), transactionReference);
        }

        auditService.log(actorUsername, "APPROVE_TRANSACTION", "Approved tx " + transactionReference + " for ₹" + formatAmount(amount), (String) null, "SUCCESS");
        return convertToResponse(saved);
    }

    @Transactional
    public TransactionResponse cancelTransaction(String transactionReference, String reason, String actorUsername) {
        Transaction tx = transactionRepository.findByTransactionReference(transactionReference)
                .orElseThrow(() -> new ResourceNotFoundException("Transaction not found: " + transactionReference));

        if (tx.getStatus() != TransactionStatus.PENDING) {
            throw new IllegalStateException("Only PENDING transactions can be cancelled. Current status is " + tx.getStatus());
        }

        tx.setStatus(TransactionStatus.CANCELLED);
        tx.setCancellationReason(reason != null && !reason.isBlank() ? reason : "Cancelled by " + actorUsername);
        tx.setCompletedAt(LocalDateTime.now());
        Transaction saved = transactionRepository.save(tx);

        if (tx.getSourceAccount() != null && tx.getSourceAccount().getUser() != null) {
            notificationService.notifySecurity(tx.getSourceAccount().getUser(), "Transaction Cancelled", 
                    "Your pending transaction (Ref: " + transactionReference + ") of ₹" + formatAmount(tx.getAmount()) + " has been CANCELLED.");
        }

        auditService.log(actorUsername, "CANCEL_TRANSACTION", "Cancelled tx " + transactionReference + " (Reason: " + tx.getCancellationReason() + ")", (String) null, "CANCELLED");
        return convertToResponse(saved);
    }

    @Transactional
    public TransactionResponse rejectTransaction(String transactionReference, String reason, String actorUsername) {
        Transaction tx = transactionRepository.findByTransactionReference(transactionReference)
                .orElseThrow(() -> new ResourceNotFoundException("Transaction not found: " + transactionReference));

        if (tx.getStatus() != TransactionStatus.PENDING) {
            throw new IllegalStateException("Only PENDING transactions can be rejected. Current status is " + tx.getStatus());
        }

        tx.setStatus(TransactionStatus.FAILED);
        tx.setFailureReason(reason != null && !reason.isBlank() ? reason : "Rejected during verification");
        tx.setCompletedAt(LocalDateTime.now());
        Transaction saved = transactionRepository.save(tx);

        if (tx.getSourceAccount() != null && tx.getSourceAccount().getUser() != null) {
            notificationService.notifySecurity(tx.getSourceAccount().getUser(), "Transaction Rejected / Failed", 
                    "Your pending transaction (Ref: " + transactionReference + ") was rejected. Reason: " + tx.getFailureReason());
        }

        auditService.log(actorUsername, "REJECT_TRANSACTION", "Rejected tx " + transactionReference + " (Reason: " + tx.getFailureReason() + ")", (String) null, "FAILED");
        return convertToResponse(saved);
    }

    private void saveFailedTransaction(Account source, Account destination, BigDecimal amount, TransactionType type, String reason, String description) {
        try {
            Transaction failedTx = Transaction.builder()
                    .transactionReference(UUID.randomUUID().toString())
                    .sourceAccount(source)
                    .destinationAccount(destination)
                    .amount(amount != null ? amount : BigDecimal.ZERO)
                    .type(type)
                    .status(TransactionStatus.FAILED)
                    .failureReason(reason)
                    .completedAt(LocalDateTime.now())
                    .description(description != null ? description : type.name())
                    .timestamp(LocalDateTime.now())
                    .build();
            transactionRepository.save(failedTx);
        } catch (Exception e) {
            System.err.println("Failed to persist failed transaction trail: " + e.getMessage());
        }
    }

    public BigDecimal getDailyDebits(Account account) {
        LocalDateTime startOfDay = LocalDate.now().atStartOfDay();
        LocalDateTime endOfDay = LocalDate.now().atTime(LocalTime.MAX);
        BigDecimal sum = transactionRepository.sumDailyDebits(account, startOfDay, endOfDay);
        return sum != null ? sum : BigDecimal.ZERO;
    }

    public List<TransactionResponse> getTransactionHistory(String accountNumber) {
        Account account = accountRepository.findByAccountNumber(accountNumber)
                .orElseThrow(() -> new ResourceNotFoundException("Account not found with number: " + accountNumber));
        return transactionRepository.findAllByAccount(account).stream()
                .map(this::convertToResponse)
                .collect(Collectors.toList());
    }

    public List<TransactionResponse> getAllTransactions() {
        return transactionRepository.findAllByOrderByTimestampDesc().stream()
                .map(this::convertToResponse)
                .collect(Collectors.toList());
    }

    public List<TransactionResponse> getPendingTransactions() {
        return transactionRepository.findByStatusOrderByTimestampDesc(TransactionStatus.PENDING).stream()
                .map(this::convertToResponse)
                .collect(Collectors.toList());
    }

    public List<TransactionResponse> getFailedTransactions() {
        return transactionRepository.findByStatusOrderByTimestampDesc(TransactionStatus.FAILED).stream()
                .map(this::convertToResponse)
                .collect(Collectors.toList());
    }

    public List<TransactionResponse> getTransactionsByStatus(TransactionStatus status) {
        return transactionRepository.findByStatusOrderByTimestampDesc(status).stream()
                .map(this::convertToResponse)
                .collect(Collectors.toList());
    }

    public List<Transaction> getTransactionsForStatement(Account account, LocalDateTime start, LocalDateTime end) {
        return transactionRepository.findByAccountAndDateRange(account, start, end);
    }

    public TransactionResponse convertToResponse(Transaction tx) {
        return TransactionResponse.builder()
                .id(tx.getId())
                .transactionReference(tx.getTransactionReference())
                .sourceAccountNumber(tx.getSourceAccount() != null ? tx.getSourceAccount().getAccountNumber() : null)
                .destinationAccountNumber(tx.getDestinationAccount() != null ? tx.getDestinationAccount().getAccountNumber() : null)
                .amount(tx.getAmount())
                .type(tx.getType().name())
                .status(tx.getStatus().name())
                .description(tx.getDescription())
                .failureReason(tx.getFailureReason())
                .reversalReference(tx.getReversalReference())
                .cancellationReason(tx.getCancellationReason())
                .idempotencyKey(tx.getIdempotencyKey())
                .npciRrn(tx.getNpciRrn())
                .payeeVpa(tx.getPayeeVpa())
                .payeeName(tx.getPayeeName())
                .merchantCode(tx.getMerchantCode())
                .completedAt(tx.getCompletedAt())
                .timestamp(tx.getTimestamp())
                .build();
    }
}
