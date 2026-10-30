package com.project.onlinebankingsystem.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.project.onlinebankingsystem.dto.*;
import com.project.onlinebankingsystem.enums.AccountStatus;
import com.project.onlinebankingsystem.enums.TransactionStatus;
import com.project.onlinebankingsystem.enums.UpiPayeeType;
import com.project.onlinebankingsystem.exception.AccountSuspendedException;
import com.project.onlinebankingsystem.exception.InsufficientBalanceException;
import com.project.onlinebankingsystem.model.Account;
import com.project.onlinebankingsystem.model.Transaction;
import com.project.onlinebankingsystem.model.User;
import com.project.onlinebankingsystem.repository.AccountRepository;
import com.project.onlinebankingsystem.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
class UpiServiceTest {

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private IdempotencyService idempotencyService;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private AuditService auditService;

    @Mock
    private NotificationService notificationService;

    private PspGatewayService pspGatewayService;
    private ObjectMapper objectMapper;
    private UpiService upiService;

    private Account testSourceAccount;
    private Account testDestinationAccount;

    @BeforeEach
    void setUp() {
        pspGatewayService = new PspGatewayService();
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());

        upiService = new UpiService(
                accountRepository,
                transactionRepository,
                pspGatewayService,
                idempotencyService,
                passwordEncoder,
                auditService,
                notificationService,
                objectMapper
        );

        User testUser = User.builder()
                .id(1L)
                .username("shubham")
                .fullName("Shubham Verma")
                .email("shubham@example.com")
                .build();

        testSourceAccount = Account.builder()
                .id(101L)
                .accountNumber("ACC-1001")
                .balance(new BigDecimal("15000.00"))
                .status(AccountStatus.ACTIVE)
                .upiId("shubham@obsbank")
                .ifscCode("APEX0001")
                .user(testUser)
                .transactionPin("hashedPin")
                .build();

        User destUser = User.builder()
                .id(2L)
                .username("rahul")
                .fullName("Rahul Sharma")
                .email("rahul@example.com")
                .build();

        testDestinationAccount = Account.builder()
                .id(102L)
                .accountNumber("ACC-2002")
                .balance(new BigDecimal("2000.00"))
                .status(AccountStatus.ACTIVE)
                .upiId("rahul@obsbank")
                .ifscCode("APEX0001")
                .user(destUser)
                .build();
    }

    @Test
    @DisplayName("Payee Validation: On-Us VPA returns verified internal KYC details")
    void testValidatePayee_OnUs() {
        when(accountRepository.findByUpiId("rahul@obsbank")).thenReturn(Optional.of(testDestinationAccount));

        UpiPayeeValidationResponse response = upiService.validatePayee("upi://pay?pa=rahul@obsbank&pn=Rahul");

        assertTrue(response.isValid());
        assertEquals("rahul@obsbank", response.getVpa());
        assertEquals("Rahul Sharma", response.getVerifiedName());
        assertTrue(response.isOnUs());
        assertEquals("Apex Horizon Bank", response.getBankName());
        assertNotNull(response.getSuggestedRrn());
        assertEquals(12, response.getSuggestedRrn().length(), "NPCI RRN must be 12 digits");
    }

    @Test
    @DisplayName("Payee Validation: Off-Us Verified Merchant VPA returns merchant registry info and limits")
    void testValidatePayee_OffUsMerchant() {
        when(accountRepository.findByUpiId("swiggy@icici")).thenReturn(Optional.empty());

        UpiPayeeValidationResponse response = upiService.validatePayee("upi://pay?pa=swiggy@icici&pn=Swiggy&am=349.50");

        assertTrue(response.isValid());
        assertEquals("swiggy@icici", response.getVpa());
        assertEquals("Swiggy Bundl Technologies Pvt Ltd", response.getVerifiedName());
        assertEquals(UpiPayeeType.MERCHANT, response.getPayeeType());
        assertFalse(response.isOnUs());
        assertEquals(new BigDecimal("349.50"), response.getFixedAmount());
        assertEquals(UpiService.UPI_P2M_MAX_LIMIT, response.getMaxAllowedAmount());
    }

    @Test
    @DisplayName("Payee Validation: Invalid VPA format returns valid=false")
    void testValidatePayee_InvalidVpa() {
        UpiPayeeValidationResponse response = upiService.validatePayee("not-a-valid-vpa");
        assertFalse(response.isValid());
        assertTrue(response.getValidationMessage().contains("Invalid UPI ID format"));
    }

    @Test
    @DisplayName("Amount Validation: P2P transfer exceeding ₹1,00,000 ceiling is rejected")
    void testValidateAmount_P2PExceeded() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                upiService.validateAndNormalizeUpiAmount(new BigDecimal("100000.01"), "friend@okhdfcbank", "0000"));

        assertTrue(ex.getMessage().contains("exceeds the NPCI UPI limit"));
    }

    @Test
    @DisplayName("Amount Validation: Minimum ₹1.00 enforced and fractional paise rejected")
    void testValidateAmount_MinAndFractionalPaise() {
        assertThrows(IllegalArgumentException.class, () ->
                upiService.validateAndNormalizeUpiAmount(new BigDecimal("0.50"), "friend@okhdfcbank", null));

        assertThrows(IllegalArgumentException.class, () ->
                upiService.validateAndNormalizeUpiAmount(new BigDecimal("10.555"), "friend@okhdfcbank", null));
    }

    @Test
    @DisplayName("Execution: Successful On-Us payment debits remitter and credits beneficiary with 12-digit RRN")
    void testExecuteUpiPayment_Success_OnUs() {
        when(accountRepository.findByAccountNumberWithLock("ACC-1001")).thenReturn(Optional.of(testSourceAccount));
        when(accountRepository.findByUpiId("rahul@obsbank")).thenReturn(Optional.of(testDestinationAccount));
        when(accountRepository.findByAccountNumberWithLock("ACC-2002")).thenReturn(Optional.of(testDestinationAccount));
        when(passwordEncoder.matches("1234", "hashedPin")).thenReturn(true);

        when(transactionRepository.save(any(Transaction.class))).thenAnswer(i -> {
            Transaction tx = i.getArgument(0);
            tx.setId(501L);
            return tx;
        });

        UpiPaymentRequest request = UpiPaymentRequest.builder()
                .sourceAccountNumber("ACC-1001")
                .payeeVpa("rahul@obsbank")
                .payeeName("Rahul Sharma")
                .amount(new BigDecimal("1500.00"))
                .pin("1234")
                .description("Lunch bill")
                .npciRrn("628019028341")
                .idempotencyKey("KEY-UPI-001")
                .build();

        UpiPaymentResponse response = upiService.executeUpiPayment(request, "shubham");

        assertNotNull(response);
        assertEquals("SUCCESS", response.getStatus());
        assertEquals("00", response.getResponseCode());
        assertEquals("628019028341", response.getNpciRrn());
        assertEquals(new BigDecimal("13500.00"), testSourceAccount.getBalance());
        assertEquals(new BigDecimal("3500.00"), testDestinationAccount.getBalance());

        verify(notificationService).notifyTransaction(eq(testSourceAccount.getUser()), eq("UPI_DEBIT"), eq(new BigDecimal("1500.00")), eq("ACC-1001"), anyString());
        verify(idempotencyService).markCompleted(eq("KEY-UPI-001"), any(TransactionResponse.class));
    }

    @Test
    @DisplayName("Duplicate Protection: Retrying same NPCI RRN returns existing receipt without double debit")
    void testExecuteUpiPayment_DuplicateRrnShield() {
        Transaction existingTx = Transaction.builder()
                .id(999L)
                .transactionReference("UPI/CR/628019028341/APEX/OK")
                .npciRrn("628019028341")
                .amount(new BigDecimal("500.00"))
                .status(TransactionStatus.SUCCESS)
                .sourceAccount(testSourceAccount)
                .payeeVpa("merchant@paytm")
                .payeeName("Star Store")
                .completedAt(LocalDateTime.now())
                .build();

        when(transactionRepository.findByNpciRrn("628019028341")).thenReturn(Optional.of(existingTx));

        BigDecimal initialBalance = testSourceAccount.getBalance();

        UpiPaymentRequest request = UpiPaymentRequest.builder()
                .sourceAccountNumber("ACC-1001")
                .payeeVpa("merchant@paytm")
                .amount(new BigDecimal("500.00"))
                .npciRrn("628019028341")
                .idempotencyKey("KEY-RETRY")
                .build();

        UpiPaymentResponse response = upiService.executeUpiPayment(request, "shubham");

        assertNotNull(response);
        assertEquals("SUCCESS", response.getStatus());
        assertTrue(response.isIdempotentReplay());
        assertTrue(response.getMessage().contains("Duplicate Payment Shield"));
        assertEquals(initialBalance, testSourceAccount.getBalance(), "Balance must NOT be debited twice");
        verify(accountRepository, never()).save(any(Account.class));
    }

    @Test
    @DisplayName("Failed Payment Handling: Incorrect UPI PIN returns ZA code and records failed transaction")
    void testExecuteUpiPayment_InvalidPin_ReturnsZA() {
        when(accountRepository.findByAccountNumberWithLock("ACC-1001")).thenReturn(Optional.of(testSourceAccount));
        when(passwordEncoder.matches("9999", "hashedPin")).thenReturn(false);

        UpiPaymentRequest request = UpiPaymentRequest.builder()
                .sourceAccountNumber("ACC-1001")
                .payeeVpa("merchant@paytm")
                .amount(new BigDecimal("500.00"))
                .pin("9999")
                .npciRrn("628019028341")
                .idempotencyKey("KEY-WRONG-PIN")
                .build();

        UpiPaymentResponse response = upiService.executeUpiPayment(request, "shubham");

        assertNotNull(response);
        assertEquals("FAILED", response.getStatus());
        assertEquals("ZA", response.getResponseCode(), "ZA is NPCI standard code for Incorrect UPI PIN");
        assertTrue(response.getMessage().contains("Incorrect 4-digit UPI PIN"));

        // Verify failure was audited and transaction recorded
        verify(transactionRepository).save(any(Transaction.class));
        verify(auditService).log(eq("shubham"), eq("FAILED_UPI_PAYMENT"), anyString(), (String) isNull(), eq("FAILED"));
    }

    @Test
    @DisplayName("Failed Payment Handling: Insufficient balance returns ZM code")
    void testExecuteUpiPayment_InsufficientBalance_ReturnsZM() {
        testSourceAccount.setBalance(new BigDecimal("100.00"));
        when(accountRepository.findByAccountNumberWithLock("ACC-1001")).thenReturn(Optional.of(testSourceAccount));
        when(passwordEncoder.matches("1234", "hashedPin")).thenReturn(true);

        UpiPaymentRequest request = UpiPaymentRequest.builder()
                .sourceAccountNumber("ACC-1001")
                .payeeVpa("merchant@paytm")
                .amount(new BigDecimal("500.00"))
                .pin("1234")
                .npciRrn("628019028341")
                .idempotencyKey("KEY-LOW-BAL")
                .build();

        UpiPaymentResponse response = upiService.executeUpiPayment(request, "shubham");

        assertNotNull(response);
        assertEquals("FAILED", response.getStatus());
        assertEquals("ZM", response.getResponseCode(), "ZM is NPCI standard code for Insufficient Funds");
        assertTrue(response.getMessage().contains("Insufficient balance"));
    }
}
