package com.project.onlinebankingsystem.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.onlinebankingsystem.dto.*;
import com.project.onlinebankingsystem.enums.AccountStatus;
import com.project.onlinebankingsystem.enums.TransactionStatus;
import com.project.onlinebankingsystem.enums.TransactionType;
import com.project.onlinebankingsystem.enums.UpiPayeeType;
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
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.text.NumberFormat;
import java.time.LocalDateTime;
import java.util.*;

@Service
public class UpiService {

    // NPCI UPI Transaction Limits
    public static final BigDecimal UPI_MIN_AMOUNT = new BigDecimal("1.00");
    public static final BigDecimal UPI_P2P_MAX_LIMIT = new BigDecimal("100000.00");  // ₹1,00,000 for Person-to-Person
    public static final BigDecimal UPI_P2M_MAX_LIMIT = new BigDecimal("200000.00");  // ₹2,00,000 for Verified Merchants
    public static final BigDecimal UPI_DAILY_LIMIT = new BigDecimal("100000.00");    // ₹1,00,000 Daily UPI cap

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final PspGatewayService pspGatewayService;
    private final IdempotencyService idempotencyService;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;
    private final NotificationService notificationService;
    private final ObjectMapper objectMapper;

    public UpiService(AccountRepository accountRepository,
                      TransactionRepository transactionRepository,
                      PspGatewayService pspGatewayService,
                      IdempotencyService idempotencyService,
                      PasswordEncoder passwordEncoder,
                      AuditService auditService,
                      NotificationService notificationService,
                      ObjectMapper objectMapper) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.pspGatewayService = pspGatewayService;
        this.idempotencyService = idempotencyService;
        this.passwordEncoder = passwordEncoder;
        this.auditService = auditService;
        this.notificationService = notificationService;
        this.objectMapper = objectMapper;
    }

    /**
     * Resolves basic account details by UPI ID (Backward compatibility).
     */
    public UpiAccountResponse findAccountByUpiId(String upiId) {
        if (upiId == null || upiId.isBlank()) {
            throw new IllegalArgumentException("UPI ID cannot be empty");
        }

        String cleanUpiId = upiId.trim();
        Account account = accountRepository.findByUpiId(cleanUpiId)
                .orElseThrow(() -> new ResourceNotFoundException("No bank account found for UPI ID: " + cleanUpiId));

        return UpiAccountResponse.builder()
                .upiId(account.getUpiId())
                .accountNumber(account.getAccountNumber())
                .accountHolderName(account.getUser() != null ? account.getUser().getFullName() : "Verified Account Holder")
                .ifscCode(account.getIfscCode())
                .bankName("Apex Horizon Bank")
                .build();
    }

    /**
     * Parses standard NPCI UPI URI or custom QR payload into structured UpiQrDetails.
     * URI Specification: upi://pay?pa=...&pn=...&mc=...&tid=...&tr=...&am=...&cu=INR&tn=...
     */
    public UpiQrDetails parseUpiQr(String qrData) {
        if (qrData == null || qrData.isBlank()) {
            throw new IllegalArgumentException("Scanned QR code data is empty.");
        }

        String clean = qrData.trim();

        // 1. Standard NPCI UPI URI
        if (clean.toLowerCase().startsWith("upi://pay")) {
            try {
                String query = clean.contains("?") ? clean.substring(clean.indexOf("?") + 1) : "";
                Map<String, String> params = parseQueryParams(query);

                String vpa = params.get("pa");
                if (vpa == null || vpa.isBlank()) {
                    throw new IllegalArgumentException("Invalid UPI QR: Payee VPA ('pa') is missing.");
                }

                String rawAm = params.get("am");
                BigDecimal amount = null;
                boolean isFixed = false;
                if (rawAm != null && !rawAm.isBlank()) {
                    try {
                        amount = new BigDecimal(rawAm).setScale(2, RoundingMode.HALF_UP);
                        isFixed = true;
                    } catch (Exception ignored) {}
                }

                return UpiQrDetails.builder()
                        .vpa(vpa.trim())
                        .payeeName(params.get("pn"))
                        .merchantCode(params.get("mc"))
                        .transactionRef(params.get("tr"))
                        .terminalId(params.get("tid"))
                        .note(params.get("tn"))
                        .amount(amount)
                        .currency(params.getOrDefault("cu", "INR"))
                        .fixedAmount(isFixed)
                        .rawQrData(clean)
                        .build();
            } catch (Exception e) {
                throw new IllegalArgumentException("Failed to parse standard UPI QR: " + e.getMessage());
            }
        }

        // 2. Custom format: UPI_ID=...
        if (clean.startsWith("UPI_ID=")) {
            String vpa = clean.replace("UPI_ID=", "").trim();
            return UpiQrDetails.builder()
                    .vpa(vpa)
                    .currency("INR")
                    .fixedAmount(false)
                    .rawQrData(clean)
                    .build();
        }

        // 3. Raw VPA entered or plain text
        if (pspGatewayService.isValidVpaFormat(clean)) {
            return UpiQrDetails.builder()
                    .vpa(clean)
                    .currency("INR")
                    .fixedAmount(false)
                    .rawQrData(clean)
                    .build();
        }

        throw new IllegalArgumentException("Invalid UPI ID format or unrecognized QR code.");
    }

    /**
     * Performs Payee Validation (VPA Verification & Account Enquiry):
     * - Validates VPA syntax.
     * - Identifies On-Us (internal) vs Off-Us (interbank NPCI switch).
     * - Returns verified name, merchant category, preset amounts, and 12-digit session RRN for confirmation screen.
     */
    public UpiPayeeValidationResponse validatePayee(String qrDataOrVpa) {
        UpiQrDetails qrDetails;
        try {
            qrDetails = parseUpiQr(qrDataOrVpa);
        } catch (Exception e) {
            return UpiPayeeValidationResponse.builder()
                    .valid(false)
                    .vpa(qrDataOrVpa)
                    .validationMessage("Invalid UPI ID format. " + (e.getMessage() != null ? e.getMessage() : "Correct format: username@bank"))
                    .build();
        }
        String vpa = qrDetails.getVpa();

        if (!pspGatewayService.isValidVpaFormat(vpa)) {
            return UpiPayeeValidationResponse.builder()
                    .valid(false)
                    .vpa(vpa)
                    .validationMessage("Invalid UPI ID format. Correct format: username@bank")
                    .build();
        }

        // 1. Check On-Us (Internal Bank Account)
        Optional<Account> internalAccountOpt = accountRepository.findByUpiId(vpa.toLowerCase());
        if (internalAccountOpt.isPresent()) {
            Account acc = internalAccountOpt.get();
            if (acc.getStatus() == AccountStatus.FROZEN) {
                return UpiPayeeValidationResponse.builder()
                        .valid(false)
                        .vpa(vpa)
                        .validationMessage("Payee bank account is currently FROZEN. Cannot receive funds.")
                        .build();
            }

            String fullName = acc.getUser() != null ? acc.getUser().getFullName() : "Apex Horizon Account Holder";
            String rrn = pspGatewayService.generateNpciRrn();

            return UpiPayeeValidationResponse.builder()
                    .valid(true)
                    .vpa(acc.getUpiId())
                    .verifiedName(fullName)
                    .payeeType(UpiPayeeType.INDIVIDUAL)
                    .merchantCategory("0000 - Personal Banking (On-Us)")
                    .bankName("Apex Horizon Bank")
                    .ifscCode(acc.getIfscCode())
                    .isOnUs(true)
                    .fixedAmount(qrDetails.getAmount())
                    .maxAllowedAmount(UPI_P2P_MAX_LIMIT)
                    .suggestedRrn(rrn)
                    .transactionNote(qrDetails.getNote())
                    .validationMessage("Verified Apex Horizon Bank Payee")
                    .maskedAccountNumber(maskAccount(acc.getAccountNumber()))
                    .build();
        }

        // 2. Off-Us: Interbank NPCI / Authorized PSP Switch Resolution
        UpiPayeeType payeeType = pspGatewayService.determinePayeeType(vpa, qrDetails.getMerchantCode());
        PspGatewayService.VerifiedMerchantInfo merchantInfo = pspGatewayService.getVerifiedMerchant(vpa);
        String bankName = pspGatewayService.resolveBankName(vpa);
        String rrn = pspGatewayService.generateNpciRrn();

        String verifiedName;
        String category;
        BigDecimal ceilingLimit;

        if (merchantInfo != null) {
            verifiedName = merchantInfo.name;
            category = merchantInfo.category;
            ceilingLimit = UPI_P2M_MAX_LIMIT;
        } else if (payeeType == UpiPayeeType.MERCHANT) {
            verifiedName = qrDetails.getPayeeName() != null && !qrDetails.getPayeeName().isBlank()
                    ? qrDetails.getPayeeName()
                    : pspGatewayService.formatDisplayNameFromVpa(vpa) + " (Merchant)";
            category = qrDetails.getMerchantCode() != null ? qrDetails.getMerchantCode() + " - Merchant Category" : "5411 - Retail Merchant";
            ceilingLimit = UPI_P2M_MAX_LIMIT;
        } else {
            verifiedName = qrDetails.getPayeeName() != null && !qrDetails.getPayeeName().isBlank()
                    ? qrDetails.getPayeeName()
                    : pspGatewayService.formatDisplayNameFromVpa(vpa);
            category = "0000 - Individual Contact (Interbank P2P)";
            ceilingLimit = UPI_P2P_MAX_LIMIT;
        }

        return UpiPayeeValidationResponse.builder()
                .valid(true)
                .vpa(vpa)
                .verifiedName(verifiedName)
                .payeeType(payeeType)
                .merchantCategory(category)
                .merchantCode(qrDetails.getMerchantCode())
                .bankName(bankName)
                .ifscCode("NPCI0000001")
                .isOnUs(false)
                .fixedAmount(qrDetails.getAmount())
                .maxAllowedAmount(ceilingLimit)
                .suggestedRrn(rrn)
                .transactionNote(qrDetails.getNote())
                .validationMessage("Verified by NPCI Interbank Switch")
                .maskedAccountNumber("NPCI-SWITCH")
                .build();
    }

    /**
     * Executes authentic UPI Payment with:
     * - Duplicate payment protection (NPCI RRN + Idempotency Key)
     * - UPI amount scale & ceiling validation
     * - TPIN / MPIN 2FA security validation
     * - Real-time debit & settlement
     * - NPCI response code failure mapping
     */
    @Transactional
    public UpiPaymentResponse executeUpiPayment(UpiPaymentRequest request, String actorUsername) {
        if (request == null) {
            throw new IllegalArgumentException("UPI Payment request cannot be null.");
        }

        // 1. Generate or validate NPCI RRN (12 digits)
        String rrn = request.getNpciRrn();
        if (rrn == null || rrn.isBlank()) {
            rrn = pspGatewayService.generateNpciRrn();
            request.setNpciRrn(rrn);
        }

        String idempotencyKey = request.getIdempotencyKey();
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            idempotencyKey = "UPI-KEY-" + rrn;
            request.setIdempotencyKey(idempotencyKey);
        }

        // 2. Duplicate Payment Protection Check
        // A: Check if this RRN was already completed in the database
        Optional<Transaction> existingTxOpt = transactionRepository.findByNpciRrn(rrn);
        if (existingTxOpt.isPresent() && existingTxOpt.get().getStatus() == TransactionStatus.SUCCESS) {
            Transaction existingTx = existingTxOpt.get();
            Account src = existingTx.getSourceAccount();
            return UpiPaymentResponse.builder()
                    .status("SUCCESS")
                    .responseCode("00")
                    .message("⚠️ Duplicate Payment Shield: Transaction was already completed successfully earlier.")
                    .transactionReference(existingTx.getTransactionReference())
                    .npciRrn(rrn)
                    .amount(existingTx.getAmount())
                    .formattedAmount(formatAmount(existingTx.getAmount()))
                    .sourceAccountNumber(maskAccount(src != null ? src.getAccountNumber() : "XXXX"))
                    .sourceAccountBalance(src != null ? src.getBalance() : BigDecimal.ZERO)
                    .payeeVpa(existingTx.getPayeeVpa())
                    .payeeName(existingTx.getPayeeName())
                    .bankName("Apex Horizon Bank")
                    .idempotencyKey(idempotencyKey)
                    .idempotentReplay(true)
                    .timestamp(existingTx.getCompletedAt() != null ? existingTx.getCompletedAt() : existingTx.getTimestamp())
                    .build();
        }

        // B: Check Idempotency Engine
        BigDecimal normalizedAmount = validateAndNormalizeUpiAmount(request.getAmount(), request.getPayeeVpa(), request.getMerchantCode());
        String reqHash = idempotencyService.computeRequestHash("UPI_PAY", request.getSourceAccountNumber(), request.getPayeeVpa(), normalizedAmount);
        TransactionResponse cachedResp = idempotencyService.checkIdempotency(idempotencyKey, "UPI_PAY", reqHash);
        if (cachedResp != null) {
            return UpiPaymentResponse.builder()
                    .status("SUCCESS")
                    .responseCode("00")
                    .message("⚠️ Transaction already processed! No duplicate debit occurred.")
                    .transactionReference(cachedResp.getTransactionReference())
                    .npciRrn(rrn)
                    .amount(cachedResp.getAmount())
                    .formattedAmount(formatAmount(cachedResp.getAmount()))
                    .sourceAccountNumber(maskAccount(request.getSourceAccountNumber()))
                    .sourceAccountBalance(BigDecimal.ZERO)
                    .payeeVpa(request.getPayeeVpa())
                    .payeeName(request.getPayeeName())
                    .idempotencyKey(idempotencyKey)
                    .idempotentReplay(true)
                    .timestamp(cachedResp.getCompletedAt() != null ? cachedResp.getCompletedAt() : cachedResp.getTimestamp())
                    .build();
        }

        idempotencyService.createProcessingRecord(idempotencyKey, "UPI_PAY", reqHash);

        Account sourceAccount = null;
        Account destinationAccount = null;
        try {
            // 3. Source & Destination Account Locking (Deadlock Prevention via Lexicographical Locking Order)
            Optional<Account> internalDestOpt = accountRepository.findByUpiId(request.getPayeeVpa().trim().toLowerCase());
            if (internalDestOpt.isPresent()) {
                String srcAccNum = request.getSourceAccountNumber().trim();
                String destAccNum = internalDestOpt.get().getAccountNumber().trim();

                if (srcAccNum.compareTo(destAccNum) < 0) {
                    sourceAccount = accountRepository.findByAccountNumberWithLock(srcAccNum)
                            .orElseThrow(() -> new ResourceNotFoundException("Debiting account not found: " + srcAccNum));
                    destinationAccount = accountRepository.findByAccountNumberWithLock(destAccNum).orElse(null);
                } else {
                    destinationAccount = accountRepository.findByAccountNumberWithLock(destAccNum).orElse(null);
                    sourceAccount = accountRepository.findByAccountNumberWithLock(srcAccNum)
                            .orElseThrow(() -> new ResourceNotFoundException("Debiting account not found: " + srcAccNum));
                }
            } else {
                sourceAccount = accountRepository.findByAccountNumberWithLock(request.getSourceAccountNumber().trim())
                        .orElseThrow(() -> new ResourceNotFoundException("Debiting account not found: " + request.getSourceAccountNumber()));
            }

            // Authorization: User can only debit their own accounts unless admin
            if (actorUsername != null && sourceAccount.getUser() != null) {
                if (!sourceAccount.getUser().getUsername().equalsIgnoreCase(actorUsername) &&
                        !actorUsername.equalsIgnoreCase("ADMIN")) {
                    throw new SecurityException("Unauthorized: Cannot debit from an account not owned by you.");
                }
            }

            // Status check (Frozen / Inactive)
            if (sourceAccount.getStatus() == AccountStatus.FROZEN) {
                throw new AccountSuspendedException("Your account is FROZEN. Please unfreeze before paying via UPI.");
            } else if (sourceAccount.getStatus() != AccountStatus.ACTIVE) {
                throw new AccountSuspendedException("Account status is " + sourceAccount.getStatus() + ". Cannot initiate UPI payment.");
            }

            // 4. UPI PIN / MPIN Validation
            if (sourceAccount.getTransactionPin() != null && !sourceAccount.getTransactionPin().isBlank()) {
                if (request.getPin() == null || !passwordEncoder.matches(request.getPin(), sourceAccount.getTransactionPin())) {
                    throw new SecurityException("Incorrect 4-digit UPI PIN. Please verify your PIN and try again.");
                }
            }

            // 5. Account Limits & Daily UPI Cap
            if (sourceAccount.getPerTransactionLimit() != null && normalizedAmount.compareTo(sourceAccount.getPerTransactionLimit()) > 0) {
                throw new IllegalArgumentException("Amount ₹" + formatAmount(normalizedAmount) + " exceeds account per-transaction limit of ₹" + formatAmount(sourceAccount.getPerTransactionLimit()));
            }

            BigDecimal dailySpent = getDailyDebits(sourceAccount);
            if (sourceAccount.getDailyLimit() != null && dailySpent.add(normalizedAmount).compareTo(sourceAccount.getDailyLimit()) > 0) {
                throw new IllegalArgumentException("Payment exceeds account daily limit of ₹" + formatAmount(sourceAccount.getDailyLimit()) + ".");
            }

            // 6. Sufficient Balance Check
            if (sourceAccount.getBalance().compareTo(normalizedAmount) < 0) {
                throw new InsufficientBalanceException("Insufficient balance in account. Available: ₹" + formatAmount(sourceAccount.getBalance()) + ", Required: ₹" + formatAmount(normalizedAmount));
            }

            // 7. Debit Remitter Account
            sourceAccount.setBalance(sourceAccount.getBalance().subtract(normalizedAmount));
            accountRepository.save(sourceAccount);

            // 8. Credit Beneficiary (On-Us Settlement)
            if (destinationAccount != null && destinationAccount.getStatus() == AccountStatus.ACTIVE) {
                destinationAccount.setBalance(destinationAccount.getBalance().add(normalizedAmount));
                accountRepository.save(destinationAccount);
            }

            // 9. Create Transaction Record
            String txRef = pspGatewayService.generateUpiTransactionReference(rrn);
            Transaction tx = Transaction.builder()
                    .transactionReference(txRef)
                    .npciRrn(rrn)
                    .payeeVpa(request.getPayeeVpa().trim())
                    .payeeName(request.getPayeeName() != null ? request.getPayeeName() : pspGatewayService.formatDisplayNameFromVpa(request.getPayeeVpa()))
                    .merchantCode(request.getMerchantCode())
                    .idempotencyKey(idempotencyKey)
                    .sourceAccount(sourceAccount)
                    .destinationAccount(destinationAccount)
                    .amount(normalizedAmount)
                    .type(TransactionType.UPI_PAYMENT)
                    .status(TransactionStatus.SUCCESS)
                    .completedAt(LocalDateTime.now())
                    .description("UPI Payment to " + request.getPayeeVpa() + (request.getDescription() != null && !request.getDescription().isBlank() ? " (" + request.getDescription() + ")" : ""))
                    .timestamp(LocalDateTime.now())
                    .build();

            Transaction saved = transactionRepository.save(tx);

            // 10. Audit & Notifications
            notificationService.notifyTransaction(sourceAccount.getUser(), "UPI_DEBIT", normalizedAmount, sourceAccount.getAccountNumber(), txRef);
            if (destinationAccount != null && destinationAccount.getUser() != null) {
                notificationService.notifyTransaction(destinationAccount.getUser(), "UPI_CREDIT", normalizedAmount, destinationAccount.getAccountNumber(), txRef);
            }
            auditService.log(actorUsername != null ? actorUsername : "USER", "UPI_PAYMENT", "Paid ₹" + formatAmount(normalizedAmount) + " to " + request.getPayeeVpa() + " (RRN: " + rrn + ")", (String) null, "SUCCESS");

            // 11. Prepare Success Response
            UpiPaymentResponse response = UpiPaymentResponse.builder()
                    .status("SUCCESS")
                    .responseCode("00")
                    .message("UPI payment of ₹" + formatAmount(normalizedAmount) + " completed successfully!")
                    .transactionReference(saved.getTransactionReference())
                    .npciRrn(rrn)
                    .amount(normalizedAmount)
                    .formattedAmount(formatAmount(normalizedAmount))
                    .sourceAccountNumber(maskAccount(sourceAccount.getAccountNumber()))
                    .sourceAccountBalance(sourceAccount.getBalance())
                    .payeeVpa(saved.getPayeeVpa())
                    .payeeName(saved.getPayeeName())
                    .bankName("Apex Horizon Bank")
                    .idempotencyKey(idempotencyKey)
                    .idempotentReplay(false)
                    .timestamp(saved.getCompletedAt())
                    .build();

            TransactionResponse txResp = TransactionResponse.builder()
                    .id(saved.getId())
                    .transactionReference(saved.getTransactionReference())
                    .amount(saved.getAmount())
                    .status("SUCCESS")
                    .idempotencyKey(idempotencyKey)
                    .completedAt(saved.getCompletedAt())
                    .build();
            idempotencyService.markCompleted(idempotencyKey, txResp);

            return response;

        } catch (Exception ex) {
            idempotencyService.markFailed(idempotencyKey);

            String responseCode = mapExceptionToNpciCode(ex);
            String failureReason = ex.getMessage();

            // Record failed transaction for auditing
            saveFailedUpiTransaction(sourceAccount, request, rrn, responseCode, failureReason);

            auditService.log(actorUsername != null ? actorUsername : "USER", "FAILED_UPI_PAYMENT", 
                    "UPI Payment of ₹" + formatAmount(normalizedAmount) + " to " + request.getPayeeVpa() + " failed: " + failureReason, (String) null, "FAILED");

            return UpiPaymentResponse.builder()
                    .status("FAILED")
                    .responseCode(responseCode)
                    .message(failureReason)
                    .transactionReference(pspGatewayService.generateUpiTransactionReference(rrn))
                    .npciRrn(rrn)
                    .amount(normalizedAmount)
                    .formattedAmount(formatAmount(normalizedAmount))
                    .sourceAccountNumber(sourceAccount != null ? maskAccount(sourceAccount.getAccountNumber()) : maskAccount(request.getSourceAccountNumber()))
                    .sourceAccountBalance(sourceAccount != null ? sourceAccount.getBalance() : BigDecimal.ZERO)
                    .payeeVpa(request.getPayeeVpa())
                    .payeeName(request.getPayeeName())
                    .bankName("Apex Horizon Bank")
                    .idempotencyKey(idempotencyKey)
                    .idempotentReplay(false)
                    .failureReason(failureReason)
                    .timestamp(LocalDateTime.now())
                    .build();
        }
    }

    /**
     * Validates and normalizes amount according to NPCI UPI specifications.
     */
    public BigDecimal validateAndNormalizeUpiAmount(BigDecimal amount, String vpa, String merchantCode) {
        if (amount == null) {
            throw new IllegalArgumentException("UPI transfer amount is required.");
        }
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("UPI transfer amount must be greater than zero (₹0.00).");
        }
        if (amount.compareTo(UPI_MIN_AMOUNT) < 0) {
            throw new IllegalArgumentException("Minimum UPI transfer amount is ₹" + formatAmount(UPI_MIN_AMOUNT) + ".");
        }
        if (amount.scale() > 2 && amount.stripTrailingZeros().scale() > 2) {
            throw new IllegalArgumentException("Invalid amount format. UPI does not support fractional paise (maximum 2 decimal places).");
        }

        BigDecimal normalized = amount.setScale(2, RoundingMode.HALF_UP);

        UpiPayeeType payeeType = pspGatewayService.determinePayeeType(vpa, merchantCode);
        BigDecimal maxLimit = (payeeType == UpiPayeeType.MERCHANT) ? UPI_P2M_MAX_LIMIT : UPI_P2P_MAX_LIMIT;

        if (normalized.compareTo(maxLimit) > 0) {
            throw new IllegalArgumentException("Transaction amount (₹" + formatAmount(normalized) + ") exceeds the NPCI UPI limit of ₹" + formatAmount(maxLimit) + " for " + payeeType.name() + " transactions.");
        }

        return normalized;
    }

    private String mapExceptionToNpciCode(Exception ex) {
        if (ex instanceof SecurityException) {
            return "ZA"; // Incorrect UPI PIN
        } else if (ex instanceof InsufficientBalanceException) {
            return "ZM"; // Insufficient Funds
        } else if (ex instanceof AccountSuspendedException) {
            return "U30"; // Account Frozen
        } else if (ex instanceof IllegalArgumentException && ex.getMessage().toLowerCase().contains("limit")) {
            return "U16"; // Limit Exceeded
        } else if (ex instanceof ResourceNotFoundException) {
            return "XB"; // Invalid VPA / Account not found
        }
        return "U96"; // System decline / Interbank processing error
    }

    private void saveFailedUpiTransaction(Account source, UpiPaymentRequest request, String rrn, String responseCode, String reason) {
        try {
            Transaction failedTx = Transaction.builder()
                    .transactionReference("FAIL-" + pspGatewayService.generateUpiTransactionReference(rrn))
                    .npciRrn(rrn)
                    .payeeVpa(request.getPayeeVpa())
                    .payeeName(request.getPayeeName())
                    .sourceAccount(source)
                    .amount(request.getAmount() != null ? request.getAmount() : BigDecimal.ZERO)
                    .type(TransactionType.UPI_PAYMENT)
                    .status(TransactionStatus.FAILED)
                    .failureReason("[" + responseCode + "] " + reason)
                    .completedAt(LocalDateTime.now())
                    .description("Failed UPI to " + request.getPayeeVpa())
                    .timestamp(LocalDateTime.now())
                    .build();
            transactionRepository.save(failedTx);
        } catch (Exception e) {
            System.err.println("Could not log failed UPI transaction: " + e.getMessage());
        }
    }

    public BigDecimal getDailyDebits(Account account) {
        LocalDateTime startOfDay = LocalDateTime.now().withHour(0).withMinute(0).withSecond(0).withNano(0);
        LocalDateTime endOfDay = LocalDateTime.now().withHour(23).withMinute(59).withSecond(59).withNano(999999999);
        BigDecimal sum = transactionRepository.sumDailyDebits(account, startOfDay, endOfDay);
        return sum != null ? sum : BigDecimal.ZERO;
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

    public String maskAccount(String accountNumber) {
        if (accountNumber == null || accountNumber.length() < 4) {
            return "XXXX";
        }
        return "XXXXXX" + accountNumber.substring(accountNumber.length() - 4);
    }

    private Map<String, String> parseQueryParams(String query) {
        Map<String, String> map = new HashMap<>();
        if (query == null || query.isBlank()) return map;

        String[] pairs = query.split("&");
        for (String pair : pairs) {
            int idx = pair.indexOf("=");
            if (idx > 0) {
                try {
                    String key = URLDecoder.decode(pair.substring(0, idx), StandardCharsets.UTF_8);
                    String value = idx < pair.length() - 1 ? URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8) : "";
                    map.put(key, value);
                } catch (Exception ignored) {}
            }
        }
        return map;
    }
}