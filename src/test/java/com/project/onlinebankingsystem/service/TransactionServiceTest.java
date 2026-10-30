package com.project.onlinebankingsystem.service;

import com.project.onlinebankingsystem.dto.TransactionResponse;
import com.project.onlinebankingsystem.enums.AccountStatus;
import com.project.onlinebankingsystem.enums.AccountType;
import com.project.onlinebankingsystem.enums.TransactionStatus;
import com.project.onlinebankingsystem.exception.AccountSuspendedException;
import com.project.onlinebankingsystem.exception.InsufficientBalanceException;
import com.project.onlinebankingsystem.model.Account;
import com.project.onlinebankingsystem.model.User;
import com.project.onlinebankingsystem.repository.AccountRepository;
import com.project.onlinebankingsystem.repository.TransactionRepository;
import com.project.onlinebankingsystem.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class TransactionServiceTest {

    @Autowired
    private TransactionService transactionService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private Account sourceAccount;
    private Account destinationAccount;
    private User testUser;

    @BeforeEach
    void setUp() {
        String uniqueSuffix = UUID.randomUUID().toString().substring(0, 8);

        testUser = userRepository.save(User.builder()
                .username("test_user_" + uniqueSuffix)
                .password(passwordEncoder.encode("Password@123"))
                .fullName("Test Customer " + uniqueSuffix)
                .email("test" + uniqueSuffix + "@bank.com")
                .phone("9" + (int) (Math.random() * 900000000 + 100000000))
                .role(com.project.onlinebankingsystem.enums.Role.ROLE_CUSTOMER)
                .build());

        sourceAccount = accountRepository.save(Account.builder()
                .accountNumber("SRC_" + uniqueSuffix)
                .ifscCode("OBIN0000101")
                .accountType(AccountType.SAVING)
                .balance(new BigDecimal("5000.00"))
                .status(AccountStatus.ACTIVE)
                .createdAt(LocalDateTime.now())
                .perTransactionLimit(new BigDecimal("50000.00"))
                .dailyLimit(new BigDecimal("100000.00"))
                .transactionPin(passwordEncoder.encode("1234"))
                .user(testUser)
                .build());

        destinationAccount = accountRepository.save(Account.builder()
                .accountNumber("DST_" + uniqueSuffix)
                .ifscCode("OBIN0000101")
                .accountType(AccountType.SAVING)
                .balance(new BigDecimal("2000.00"))
                .status(AccountStatus.ACTIVE)
                .createdAt(LocalDateTime.now())
                .perTransactionLimit(new BigDecimal("50000.00"))
                .dailyLimit(new BigDecimal("100000.00"))
                .user(testUser)
                .build());
    }

    @Test
    @DisplayName("₹1,000 Transfer: Successful debit and credit with status=SUCCESS")
    void testTransfer_1000Rupees_Success() {
        BigDecimal transferAmount = new BigDecimal("1000.00");

        TransactionResponse response = transactionService.transfer(
                sourceAccount.getAccountNumber(),
                destinationAccount.getAccountNumber(),
                transferAmount,
                "Rent Payment",
                "1234"
        );

        assertNotNull(response);
        assertEquals("SUCCESS", response.getStatus());
        assertEquals(transferAmount, response.getAmount());
        assertNotNull(response.getTransactionReference());

        Account updatedSource = accountRepository.findByAccountNumber(sourceAccount.getAccountNumber()).orElseThrow();
        Account updatedDest = accountRepository.findByAccountNumber(destinationAccount.getAccountNumber()).orElseThrow();

        assertEquals(new BigDecimal("4000.00"), updatedSource.getBalance());
        assertEquals(new BigDecimal("3000.00"), updatedDest.getBalance());
    }

    @Test
    @DisplayName("Insufficient Balance: Source has ₹500, transferring ₹1,000 throws InsufficientBalanceException")
    void testTransfer_InsufficientBalance() {
        sourceAccount.setBalance(new BigDecimal("500.00"));
        accountRepository.save(sourceAccount);

        InsufficientBalanceException ex = assertThrows(InsufficientBalanceException.class, () ->
                transactionService.transfer(
                        sourceAccount.getAccountNumber(),
                        destinationAccount.getAccountNumber(),
                        new BigDecimal("1000.00"),
                        "Transfer",
                        "1234"
                )
        );

        assertTrue(ex.getMessage().contains("Insufficient balance"));

        // Verify balance was untouched
        Account currentSource = accountRepository.findByAccountNumber(sourceAccount.getAccountNumber()).orElseThrow();
        assertEquals(new BigDecimal("500.00"), currentSource.getBalance());
    }

    @Test
    @DisplayName("Negative Amount: Transfer with -₹1,000 throws IllegalArgumentException")
    void testTransfer_NegativeAmount() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                transactionService.transfer(
                        sourceAccount.getAccountNumber(),
                        destinationAccount.getAccountNumber(),
                        new BigDecimal("-1000.00"),
                        "Negative transfer",
                        "1234"
                )
        );

        assertTrue(ex.getMessage().contains("strictly greater than zero"));
    }

    @Test
    @DisplayName("Zero Amount: Transfer with ₹0.00 throws IllegalArgumentException")
    void testTransfer_ZeroAmount() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                transactionService.transfer(
                        sourceAccount.getAccountNumber(),
                        destinationAccount.getAccountNumber(),
                        BigDecimal.ZERO,
                        "Zero transfer",
                        "1234"
                )
        );

        assertTrue(ex.getMessage().contains("strictly greater than zero"));
    }

    @Test
    @DisplayName("Suspended Account: Frozen source account rejected with AccountSuspendedException")
    void testTransfer_SuspendedSourceAccount_Frozen() {
        sourceAccount.setStatus(AccountStatus.FROZEN);
        accountRepository.save(sourceAccount);

        AccountSuspendedException ex = assertThrows(AccountSuspendedException.class, () ->
                transactionService.transfer(
                        sourceAccount.getAccountNumber(),
                        destinationAccount.getAccountNumber(),
                        new BigDecimal("1000.00"),
                        "Frozen account transfer",
                        "1234"
                )
        );

        assertTrue(ex.getMessage().contains("FROZEN"));
    }

    @Test
    @DisplayName("Suspended Account: Suspended destination account rejected with AccountSuspendedException")
    void testTransfer_SuspendedDestinationAccount() {
        destinationAccount.setStatus(AccountStatus.SUSPENDED);
        accountRepository.save(destinationAccount);

        AccountSuspendedException ex = assertThrows(AccountSuspendedException.class, () ->
                transactionService.transfer(
                        sourceAccount.getAccountNumber(),
                        destinationAccount.getAccountNumber(),
                        new BigDecimal("1000.00"),
                        "Transfer to suspended",
                        "1234"
                )
        );

        assertTrue(ex.getMessage().contains("SUSPENDED"));
    }

    @Test
    @DisplayName("Same Account Transfer: Source and Destination being identical rejected")
    void testTransfer_SameAccount() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                transactionService.transfer(
                        sourceAccount.getAccountNumber(),
                        sourceAccount.getAccountNumber(),
                        new BigDecimal("1000.00"),
                        "Self transfer",
                        "1234"
                )
        );

        assertEquals("Source and destination account numbers must be different.", ex.getMessage());
    }

    @Test
    @DisplayName("Unauthorized Access / Invalid PIN: Wrong transaction PIN throws SecurityException")
    void testTransfer_InvalidTransactionPin() {
        SecurityException ex = assertThrows(SecurityException.class, () ->
                transactionService.transfer(
                        sourceAccount.getAccountNumber(),
                        destinationAccount.getAccountNumber(),
                        new BigDecimal("1000.00"),
                        "Transfer with wrong pin",
                        "9999" // Wrong PIN
                )
        );

        assertTrue(ex.getMessage().contains("Invalid 4-digit Transaction PIN"));
    }

    @Test
    @DisplayName("Duplicate Transaction Protection: Replaying same idempotency key does not double-debit")
    void testTransfer_DuplicateTransaction_Idempotency() {
        String idempKey = "TX-IDEMP-" + UUID.randomUUID();
        BigDecimal amount = new BigDecimal("1000.00");

        // First attempt -> Execution
        TransactionResponse firstResponse = transactionService.transfer(
                sourceAccount.getAccountNumber(),
                destinationAccount.getAccountNumber(),
                amount,
                "Payment",
                "1234",
                idempKey
        );
        assertNotNull(firstResponse);
        assertFalse(firstResponse.isIdempotentReplay());

        Account afterFirst = accountRepository.findByAccountNumber(sourceAccount.getAccountNumber()).orElseThrow();
        assertEquals(new BigDecimal("4000.00"), afterFirst.getBalance());

        // Second attempt with identical key -> Replay cached response without re-debiting
        TransactionResponse replayResponse = transactionService.transfer(
                sourceAccount.getAccountNumber(),
                destinationAccount.getAccountNumber(),
                amount,
                "Payment",
                "1234",
                idempKey
        );
        assertNotNull(replayResponse);
        assertTrue(replayResponse.isIdempotentReplay());
        assertEquals(firstResponse.getTransactionReference(), replayResponse.getTransactionReference());

        // Balance remains ₹4,000.00 (not debited twice!)
        Account afterReplay = accountRepository.findByAccountNumber(sourceAccount.getAccountNumber()).orElseThrow();
        assertEquals(new BigDecimal("4000.00"), afterReplay.getBalance());
    }

    @Test
    @DisplayName("Simultaneous Transfers: 10 concurrent threads transfer ₹1,000 from ₹5,000 balance -> Exactly 5 succeed, 5 fail, balance never goes negative")
    void testTransfer_SimultaneousTransfers_Concurrency() throws InterruptedException {
        int threadCount = 10;
        BigDecimal transferAmount = new BigDecimal("1000.00"); // Source has ₹5,000

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threadCount);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            executor.submit(() -> {
                try {
                    startLatch.await(); // Ensure all threads launch at the exact same moment
                    transactionService.transfer(
                            sourceAccount.getAccountNumber(),
                            destinationAccount.getAccountNumber(),
                            transferAmount,
                            "Concurrent Transfer " + index,
                            "1234"
                    );
                    successCount.incrementAndGet();
                } catch (InsufficientBalanceException ex) {
                    failureCount.incrementAndGet();
                } catch (Exception ex) {
                    // Any other concurrency or DB error
                    failureCount.incrementAndGet();
                } finally {
                    endLatch.countDown();
                }
            });
        }

        // Fire all threads simultaneously
        startLatch.countDown();
        boolean completed = endLatch.await(15, TimeUnit.SECONDS);
        executor.shutdown();

        assertTrue(completed, "Concurrent transfers did not complete within timeout");

        // Exactly 5 transfers should succeed from ₹5,000 balance
        assertEquals(5, successCount.get(), "Expected exactly 5 transfers to succeed from ₹5,000 balance");
        assertEquals(5, failureCount.get(), "Expected exactly 5 transfers to fail due to insufficient funds");

        Account finalSource = accountRepository.findByAccountNumber(sourceAccount.getAccountNumber()).orElseThrow();
        Account finalDest = accountRepository.findByAccountNumber(destinationAccount.getAccountNumber()).orElseThrow();

        // Source balance must be exactly ₹0.00, never negative
        assertEquals(new BigDecimal("0.00"), finalSource.getBalance(), "Final source balance must be exactly 0.00");
        // Destination started with ₹2,000 and received exactly 5 * ₹1,000 = ₹7,000
        assertEquals(new BigDecimal("7000.00"), finalDest.getBalance(), "Final destination balance must be exactly 7,000.00");
    }
}
