package com.project.onlinebankingsystem.service;

import com.project.onlinebankingsystem.enums.AccountStatus;
import com.project.onlinebankingsystem.enums.AccountType;
import com.project.onlinebankingsystem.exception.InsufficientBalanceException;
import com.project.onlinebankingsystem.model.Account;
import com.project.onlinebankingsystem.model.User;
import com.project.onlinebankingsystem.repository.AccountRepository;
import com.project.onlinebankingsystem.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TransactionValidationTest {

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private AuditService auditService;

    @Mock
    private NotificationService notificationService;

    @Mock
    private IdempotencyService idempotencyService;

    @InjectMocks
    private TransactionService transactionService;

    private Account testAccount;
    private Account destinationAccount;

    @BeforeEach
    void setUp() {
        User testUser = User.builder()
                .id(1L)
                .username("testuser")
                .fullName("Test User")
                .email("test@example.com")
                .build();

        testAccount = Account.builder()
                .id(1L)
                .accountNumber("ACC123456")
                .balance(new BigDecimal("25000.00"))
                .status(AccountStatus.ACTIVE)
                .accountType(AccountType.SAVING)
                .perTransactionLimit(new BigDecimal("50000.00"))
                .dailyLimit(new BigDecimal("100000.00"))
                .user(testUser)
                .createdAt(LocalDateTime.now())
                .build();

        destinationAccount = Account.builder()
                .id(2L)
                .accountNumber("ACC654321")
                .balance(new BigDecimal("10000.00"))
                .status(AccountStatus.ACTIVE)
                .accountType(AccountType.CURRENT)
                .perTransactionLimit(new BigDecimal("50000.00"))
                .dailyLimit(new BigDecimal("100000.00"))
                .user(testUser)
                .createdAt(LocalDateTime.now())
                .build();
    }

    @Test
    @DisplayName("Should reject null amount")
    void testValidateAmount_Null() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                transactionService.validateAndNormalizeAmount(null));
        assertTrue(ex.getMessage().contains("required and cannot be empty"));
    }

    @Test
    @DisplayName("Should reject zero and negative amounts")
    void testValidateAmount_ZeroAndNegative() {
        IllegalArgumentException zeroEx = assertThrows(IllegalArgumentException.class, () ->
                transactionService.validateAndNormalizeAmount(BigDecimal.ZERO));
        assertTrue(zeroEx.getMessage().contains("strictly greater than zero"));

        IllegalArgumentException negEx = assertThrows(IllegalArgumentException.class, () ->
                transactionService.validateAndNormalizeAmount(new BigDecimal("-50.00")));
        assertTrue(negEx.getMessage().contains("strictly greater than zero"));
    }

    @Test
    @DisplayName("Should reject amount less than minimum threshold ₹1.00")
    void testValidateAmount_LessThanMinimum() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                transactionService.validateAndNormalizeAmount(new BigDecimal("0.50")));
        assertTrue(ex.getMessage().contains("Minimum transaction amount allowed is ₹1.00"));
    }

    @Test
    @DisplayName("Should reject amounts with more than 2 decimal places (fractions of paise)")
    void testValidateAmount_InvalidScale() {
        IllegalArgumentException ex1 = assertThrows(IllegalArgumentException.class, () ->
                transactionService.validateAndNormalizeAmount(new BigDecimal("10.555")));
        assertTrue(ex1.getMessage().contains("cannot exceed 2 decimal places"));

        IllegalArgumentException ex2 = assertThrows(IllegalArgumentException.class, () ->
                transactionService.validateAndNormalizeAmount(new BigDecimal("100.12345")));
        assertTrue(ex2.getMessage().contains("cannot exceed 2 decimal places"));
    }

    @Test
    @DisplayName("Should accept valid decimals with 0, 1, or 2 decimal places and normalize them")
    void testValidateAmount_ValidDecimals() {
        BigDecimal res1 = transactionService.validateAndNormalizeAmount(new BigDecimal("100"));
        assertEquals(new BigDecimal("100.00"), res1);

        BigDecimal res2 = transactionService.validateAndNormalizeAmount(new BigDecimal("100.5"));
        assertEquals(new BigDecimal("100.50"), res2);

        BigDecimal res3 = transactionService.validateAndNormalizeAmount(new BigDecimal("100.75"));
        assertEquals(new BigDecimal("100.75"), res3);

        BigDecimal res4 = transactionService.validateAndNormalizeAmount(new BigDecimal("100.500"));
        assertEquals(new BigDecimal("100.50"), res4);
    }

    @Test
    @DisplayName("Should reject amount exceeding system ceiling limit of ₹10,00,000.00")
    void testValidateAmount_ExceedsSystemCeiling() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                transactionService.validateAndNormalizeAmount(new BigDecimal("1000001.00")));
        assertTrue(ex.getMessage().contains("exceeds the maximum permissible limit"));
    }

    @Test
    @DisplayName("Withdrawal: Should enforce per-transaction limit")
    void testWithdraw_ExceedsPerTransactionLimit() {
        when(accountRepository.findByAccountNumberWithLock("ACC123456")).thenReturn(Optional.of(testAccount));

        // Account perTransactionLimit is 50,000; attempting to withdraw 60,000
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                transactionService.withdraw("ACC123456", new BigDecimal("60000.00"), "ATM Withdrawal", null));
        assertTrue(ex.getMessage().contains("exceeds account per-transaction limit"));
    }

    @Test
    @DisplayName("Withdrawal: Should enforce daily limit")
    void testWithdraw_ExceedsDailyLimit() {
        when(accountRepository.findByAccountNumberWithLock("ACC123456")).thenReturn(Optional.of(testAccount));
        when(transactionRepository.sumDailyDebits(eq(testAccount), any(), any())).thenReturn(new BigDecimal("80000.00"));

        // Daily limit is 100,000; already spent 80,000; attempting 30,000 (total 110,000)
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                transactionService.withdraw("ACC123456", new BigDecimal("30000.00"), "ATM Withdrawal", null));
        assertTrue(ex.getMessage().contains("exceeds account daily limit"));
    }

    @Test
    @DisplayName("Withdrawal: Should reject when balance is insufficient")
    void testWithdraw_InsufficientBalance() {
        when(accountRepository.findByAccountNumberWithLock("ACC123456")).thenReturn(Optional.of(testAccount));
        when(transactionRepository.sumDailyDebits(eq(testAccount), any(), any())).thenReturn(BigDecimal.ZERO);

        // Account balance is 25,000; attempting to withdraw 30,000
        InsufficientBalanceException ex = assertThrows(InsufficientBalanceException.class, () ->
                transactionService.withdraw("ACC123456", new BigDecimal("30000.00"), "ATM Withdrawal", null));
        assertTrue(ex.getMessage().contains("Insufficient balance in account"));
    }

    @Test
    @DisplayName("Deposit: Should reject negative or fractional amounts")
    void testDeposit_Validation() {
        assertThrows(IllegalArgumentException.class, () ->
                transactionService.deposit("ACC123456", new BigDecimal("-500.00"), "Deposit"));
        assertThrows(IllegalArgumentException.class, () ->
                transactionService.deposit("ACC123456", new BigDecimal("50.555"), "Deposit"));
        assertThrows(IllegalArgumentException.class, () ->
                transactionService.deposit("ACC123456", null, "Deposit"));
    }

    @Test
    @DisplayName("Transfer: Should reject identical source and destination accounts")
    void testTransfer_SameAccount() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                transactionService.transfer("ACC123456", "ACC123456", new BigDecimal("500.00"), "Transfer", null));
        assertTrue(ex.getMessage().contains("Source and destination account numbers must be different"));
    }

    @Test
    @DisplayName("DTO Validation: TransferRequest should enforce NotNull, DecimalMin, DecimalMax, and Digits")
    void testTransferRequest_DtoValidation() {
        jakarta.validation.Validator validator = jakarta.validation.Validation.buildDefaultValidatorFactory().getValidator();

        // 1. Null amount
        com.project.onlinebankingsystem.dto.TransferRequest req1 = new com.project.onlinebankingsystem.dto.TransferRequest("ACC1", "ACC2", null, "Test", "1234");
        assertFalse(validator.validate(req1).isEmpty());

        // 2. Amount < 1.00
        com.project.onlinebankingsystem.dto.TransferRequest req2 = new com.project.onlinebankingsystem.dto.TransferRequest("ACC1", "ACC2", new BigDecimal("0.50"), "Test", "1234");
        assertFalse(validator.validate(req2).isEmpty());

        // 3. Amount > 10,00,000.00
        com.project.onlinebankingsystem.dto.TransferRequest req3 = new com.project.onlinebankingsystem.dto.TransferRequest("ACC1", "ACC2", new BigDecimal("1000001.00"), "Test", "1234");
        assertFalse(validator.validate(req3).isEmpty());

        // 4. Fractions of a paisa (scale > 2)
        com.project.onlinebankingsystem.dto.TransferRequest req4 = new com.project.onlinebankingsystem.dto.TransferRequest("ACC1", "ACC2", new BigDecimal("100.555"), "Test", "1234");
        assertFalse(validator.validate(req4).isEmpty());

        // 5. Valid TransferRequest
        com.project.onlinebankingsystem.dto.TransferRequest reqValid = new com.project.onlinebankingsystem.dto.TransferRequest("ACC1", "ACC2", new BigDecimal("500.50"), "Test", "1234");
        assertTrue(validator.validate(reqValid).isEmpty());
    }

    @Test
    @DisplayName("DTO Validation: DepositWithdrawRequest should enforce NotNull, DecimalMin, DecimalMax, and Digits")
    void testDepositWithdrawRequest_DtoValidation() {
        jakarta.validation.Validator validator = jakarta.validation.Validation.buildDefaultValidatorFactory().getValidator();

        // 1. Null amount
        com.project.onlinebankingsystem.dto.DepositWithdrawRequest req1 = new com.project.onlinebankingsystem.dto.DepositWithdrawRequest("ACC1", null, "Test", "1234");
        assertFalse(validator.validate(req1).isEmpty());

        // 2. Amount < 1.00
        com.project.onlinebankingsystem.dto.DepositWithdrawRequest req2 = new com.project.onlinebankingsystem.dto.DepositWithdrawRequest("ACC1", new BigDecimal("0.99"), "Test", "1234");
        assertFalse(validator.validate(req2).isEmpty());

        // 3. Amount > 10,00,000.00
        com.project.onlinebankingsystem.dto.DepositWithdrawRequest req3 = new com.project.onlinebankingsystem.dto.DepositWithdrawRequest("ACC1", new BigDecimal("1000001.00"), "Test", "1234");
        assertFalse(validator.validate(req3).isEmpty());

        // 4. Scale > 2
        com.project.onlinebankingsystem.dto.DepositWithdrawRequest req4 = new com.project.onlinebankingsystem.dto.DepositWithdrawRequest("ACC1", new BigDecimal("25.123"), "Test", "1234");
        assertFalse(validator.validate(req4).isEmpty());

        // 5. Valid DepositWithdrawRequest
        com.project.onlinebankingsystem.dto.DepositWithdrawRequest reqValid = new com.project.onlinebankingsystem.dto.DepositWithdrawRequest("ACC1", new BigDecimal("25.50"), "Test", "1234");
        assertTrue(validator.validate(reqValid).isEmpty());
    }

    @Test
    @DisplayName("Lifecycle: Initiate Pending Transfer sets status to PENDING")
    void testInitiatePendingTransfer() {
        when(accountRepository.findByAccountNumber("ACC123456")).thenReturn(Optional.of(testAccount));
        when(accountRepository.findByAccountNumber("ACC654321")).thenReturn(Optional.of(destinationAccount));
        when(transactionRepository.save(any(com.project.onlinebankingsystem.model.Transaction.class))).thenAnswer(inv -> inv.getArgument(0));

        com.project.onlinebankingsystem.dto.TransactionResponse resp = transactionService.initiatePendingTransfer(
                "ACC123456", "ACC654321", new BigDecimal("1500.00"), "Rent Payment", null);

        assertNotNull(resp);
        assertEquals(com.project.onlinebankingsystem.enums.TransactionStatus.PENDING.name(), resp.getStatus());
        assertTrue(resp.getTransactionReference().startsWith("TXN-PND-"));
    }

    @Test
    @DisplayName("Lifecycle: Approve PENDING transaction transitions to SUCCESS and updates balances")
    void testApprovePendingTransaction() {
        com.project.onlinebankingsystem.model.Transaction pendingTx = com.project.onlinebankingsystem.model.Transaction.builder()
                .id(10L)
                .transactionReference("TXN-PND-TEST01")
                .sourceAccount(testAccount)
                .destinationAccount(destinationAccount)
                .amount(new BigDecimal("5000.00"))
                .type(com.project.onlinebankingsystem.enums.TransactionType.TRANSFER)
                .status(com.project.onlinebankingsystem.enums.TransactionStatus.PENDING)
                .timestamp(LocalDateTime.now())
                .build();

        when(transactionRepository.findByTransactionReference("TXN-PND-TEST01")).thenReturn(Optional.of(pendingTx));
        when(accountRepository.findByAccountNumberWithLock("ACC123456")).thenReturn(Optional.of(testAccount));
        when(accountRepository.findByAccountNumberWithLock("ACC654321")).thenReturn(Optional.of(destinationAccount));
        when(transactionRepository.save(any(com.project.onlinebankingsystem.model.Transaction.class))).thenAnswer(inv -> inv.getArgument(0));

        BigDecimal initialSourceBalance = testAccount.getBalance();
        BigDecimal initialDestBalance = destinationAccount.getBalance();

        com.project.onlinebankingsystem.dto.TransactionResponse resp = transactionService.approveTransaction("TXN-PND-TEST01", "ADMIN");

        assertEquals(com.project.onlinebankingsystem.enums.TransactionStatus.SUCCESS.name(), resp.getStatus());
        assertNotNull(resp.getCompletedAt());
        assertEquals(initialSourceBalance.subtract(new BigDecimal("5000.00")), testAccount.getBalance());
        assertEquals(initialDestBalance.add(new BigDecimal("5000.00")), destinationAccount.getBalance());
    }

    @Test
    @DisplayName("Lifecycle: Cancel PENDING transaction transitions to CANCELLED without touching balances")
    void testCancelPendingTransaction() {
        com.project.onlinebankingsystem.model.Transaction pendingTx = com.project.onlinebankingsystem.model.Transaction.builder()
                .id(11L)
                .transactionReference("TXN-PND-TEST02")
                .sourceAccount(testAccount)
                .destinationAccount(destinationAccount)
                .amount(new BigDecimal("2000.00"))
                .type(com.project.onlinebankingsystem.enums.TransactionType.TRANSFER)
                .status(com.project.onlinebankingsystem.enums.TransactionStatus.PENDING)
                .timestamp(LocalDateTime.now())
                .build();

        when(transactionRepository.findByTransactionReference("TXN-PND-TEST02")).thenReturn(Optional.of(pendingTx));
        when(transactionRepository.save(any(com.project.onlinebankingsystem.model.Transaction.class))).thenAnswer(inv -> inv.getArgument(0));

        BigDecimal sourceBalanceBefore = testAccount.getBalance();

        com.project.onlinebankingsystem.dto.TransactionResponse resp = transactionService.cancelTransaction(
                "TXN-PND-TEST02", "User changed mind", "testuser");

        assertEquals(com.project.onlinebankingsystem.enums.TransactionStatus.CANCELLED.name(), resp.getStatus());
        assertEquals("User changed mind", resp.getCancellationReason());
        assertNotNull(resp.getCompletedAt());
        assertEquals(sourceBalanceBefore, testAccount.getBalance());
    }

    @Test
    @DisplayName("Lifecycle: Reject PENDING transaction transitions to FAILED")
    void testRejectPendingTransaction() {
        com.project.onlinebankingsystem.model.Transaction pendingTx = com.project.onlinebankingsystem.model.Transaction.builder()
                .id(12L)
                .transactionReference("TXN-PND-TEST03")
                .sourceAccount(testAccount)
                .destinationAccount(destinationAccount)
                .amount(new BigDecimal("1000.00"))
                .type(com.project.onlinebankingsystem.enums.TransactionType.TRANSFER)
                .status(com.project.onlinebankingsystem.enums.TransactionStatus.PENDING)
                .timestamp(LocalDateTime.now())
                .build();

        when(transactionRepository.findByTransactionReference("TXN-PND-TEST03")).thenReturn(Optional.of(pendingTx));
        when(transactionRepository.save(any(com.project.onlinebankingsystem.model.Transaction.class))).thenAnswer(inv -> inv.getArgument(0));

        com.project.onlinebankingsystem.dto.TransactionResponse resp = transactionService.rejectTransaction(
                "TXN-PND-TEST03", "Compliance failure", "ADMIN");

        assertEquals(com.project.onlinebankingsystem.enums.TransactionStatus.FAILED.name(), resp.getStatus());
        assertEquals("Compliance failure", resp.getFailureReason());
        assertNotNull(resp.getCompletedAt());
    }

    @Test
    @DisplayName("Lifecycle: Cannot cancel a SUCCESS transaction")
    void testCannotCancelSuccessTransaction() {
        com.project.onlinebankingsystem.model.Transaction successTx = com.project.onlinebankingsystem.model.Transaction.builder()
                .id(13L)
                .transactionReference("TXN-SUCC-01")
                .sourceAccount(testAccount)
                .destinationAccount(destinationAccount)
                .amount(new BigDecimal("1000.00"))
                .type(com.project.onlinebankingsystem.enums.TransactionType.TRANSFER)
                .status(com.project.onlinebankingsystem.enums.TransactionStatus.SUCCESS)
                .timestamp(LocalDateTime.now())
                .build();

        when(transactionRepository.findByTransactionReference("TXN-SUCC-01")).thenReturn(Optional.of(successTx));

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                transactionService.cancelTransaction("TXN-SUCC-01", "Try cancel", "testuser"));
        assertTrue(ex.getMessage().contains("Only PENDING transactions can be cancelled"));
    }

    @Test
    @DisplayName("Entity & Enum: Status helpers should return correct booleans")
    void testTransactionModelStatusHelpers() {
        com.project.onlinebankingsystem.model.Transaction tx = new com.project.onlinebankingsystem.model.Transaction();

        tx.setStatus(com.project.onlinebankingsystem.enums.TransactionStatus.PENDING);
        assertTrue(tx.isPending());
        assertFalse(tx.isSuccess());
        assertTrue(tx.canBeCancelled());
        assertFalse(tx.canBeReversed());

        tx.setStatus(com.project.onlinebankingsystem.enums.TransactionStatus.SUCCESS);
        assertTrue(tx.isSuccess());
        assertFalse(tx.isPending());
        assertFalse(tx.canBeCancelled());
        assertTrue(tx.canBeReversed());

        tx.setStatus(com.project.onlinebankingsystem.enums.TransactionStatus.FAILED);
        assertTrue(tx.isFailed());

        tx.setStatus(com.project.onlinebankingsystem.enums.TransactionStatus.REVERSED);
        assertTrue(tx.isReversed());

        tx.setStatus(com.project.onlinebankingsystem.enums.TransactionStatus.CANCELLED);
        assertTrue(tx.isCancelled());
    }

    @Test
    @DisplayName("Idempotency: Retrying transfer with same idempotency key returns cached response without double debit")
    void testTransferWithIdempotencyReplay_DoesNotDoubleDebit() {
        String idempotencyKey = "PAY-UUID-12345-ABCDE";
        String expectedHash = "dummyhash123";

        when(idempotencyService.computeRequestHash(eq("TRANSFER"), eq("ACC-1001"), eq("ACC-2002"), any(BigDecimal.class)))
                .thenReturn(expectedHash);

        com.project.onlinebankingsystem.dto.TransactionResponse cachedResponse = com.project.onlinebankingsystem.dto.TransactionResponse.builder()
                .transactionReference("TXN-ALREADY-DONE-001")
                .sourceAccountNumber("ACC-1001")
                .destinationAccountNumber("ACC-2002")
                .amount(new BigDecimal("500.00"))
                .status("SUCCESS")
                .idempotencyKey(idempotencyKey)
                .idempotentReplay(true)
                .build();

        // Simulate that checkIdempotency finds an already completed record
        when(idempotencyService.checkIdempotency(eq(idempotencyKey), eq("TRANSFER"), eq(expectedHash)))
                .thenReturn(cachedResponse);

        BigDecimal balanceBefore = testAccount.getBalance();

        com.project.onlinebankingsystem.dto.TransactionResponse response = transactionService.transfer(
                "ACC-1001", "ACC-2002", new BigDecimal("500.00"), "Payment retry", null, idempotencyKey);

        assertNotNull(response);
        assertEquals("TXN-ALREADY-DONE-001", response.getTransactionReference());
        assertTrue(response.isIdempotentReplay());

        // CRUCIAL: Verify that account balance was NEVER debited and no new transaction was saved!
        assertEquals(balanceBefore, testAccount.getBalance());
        verify(accountRepository, never()).save(any(Account.class));
        verify(transactionRepository, never()).save(any(com.project.onlinebankingsystem.model.Transaction.class));
        verify(idempotencyService, never()).createProcessingRecord(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("Idempotency: Concurrent retry while transaction is PROCESSING throws IllegalStateException")
    void testTransferWithConcurrentProcessing_ThrowsException() {
        String idempotencyKey = "PAY-CONCURRENT-KEY";
        String expectedHash = "hash789";

        when(idempotencyService.computeRequestHash(eq("TRANSFER"), eq("ACC-1001"), eq("ACC-2002"), any(BigDecimal.class)))
                .thenReturn(expectedHash);

        when(idempotencyService.checkIdempotency(eq(idempotencyKey), eq("TRANSFER"), eq(expectedHash)))
                .thenThrow(new IllegalStateException("Transaction with request key is currently in progress."));

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                transactionService.transfer("ACC-1001", "ACC-2002", new BigDecimal("500.00"), "Concurrent retry", null, idempotencyKey));

        assertTrue(ex.getMessage().contains("currently in progress"));
        verify(accountRepository, never()).save(any(Account.class));
    }

    @Test
    @DisplayName("Idempotency: Reusing idempotency key with different payload throws IllegalArgumentException")
    void testTransferWithIdempotencyConflict_ThrowsException() {
        String idempotencyKey = "REUSED-KEY-DIFFERENT-AMOUNT";
        String expectedHash = "newhash456";

        when(idempotencyService.computeRequestHash(eq("TRANSFER"), eq("ACC-1001"), eq("ACC-2002"), any(BigDecimal.class)))
                .thenReturn(expectedHash);

        when(idempotencyService.checkIdempotency(eq(idempotencyKey), eq("TRANSFER"), eq(expectedHash)))
                .thenThrow(new IllegalArgumentException("Idempotency Conflict: Key was already used with a different transaction payload."));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                transactionService.transfer("ACC-1001", "ACC-2002", new BigDecimal("500.00"), "Tampered payload", null, idempotencyKey));

        assertTrue(ex.getMessage().contains("Idempotency Conflict"));
        verify(accountRepository, never()).save(any(Account.class));
    }
}
