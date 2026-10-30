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
class AccountServiceTest {

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private AuditService auditService;

    @Mock
    private NotificationService notificationService;

    @InjectMocks
    private AccountService accountService;

    private User sampleUser;
    private Account sampleAccount;

    @BeforeEach
    void setUp() {
        sampleUser = User.builder()
                .id(1L)
                .username("priya_patel")
                .fullName("Priya Patel")
                .email("priya@example.com")
                .build();

        sampleAccount = Account.builder()
                .id(10L)
                .accountNumber("1002003004")
                .upiId("priyapatel@obsbank")
                .ifscCode("OBIN0000101")
                .accountType(AccountType.SAVING)
                .balance(new BigDecimal("25000.00"))
                .status(AccountStatus.ACTIVE)
                .createdAt(LocalDateTime.now())
                .perTransactionLimit(new BigDecimal("50000.00"))
                .dailyLimit(new BigDecimal("100000.00"))
                .user(sampleUser)
                .build();
    }

    @Test
    @DisplayName("Create Account: Success creates active account with UPI ID")
    void testCreateAccount_Success() {
        AccountRequest request = new AccountRequest();
        request.setAccountType("SAVING");

        when(userRepository.findByUsername("priya_patel")).thenReturn(Optional.of(sampleUser));
        when(accountRepository.existsByAccountNumber(anyString())).thenReturn(false);
        when(accountRepository.findByUpiId(anyString())).thenReturn(Optional.empty());
        when(accountRepository.save(any(Account.class))).thenAnswer(invocation -> {
            Account acc = invocation.getArgument(0);
            acc.setId(11L);
            return acc;
        });

        AccountResponse response = accountService.createAccount("priya_patel", request);

        assertNotNull(response);
        assertNotNull(response.getAccountNumber());
        assertTrue(response.getUpiId().contains("priyapatel"));
        assertEquals("SAVING", response.getAccountType());
        assertEquals(AccountStatus.ACTIVE.name(), response.getStatus());
        assertEquals("priya_patel", response.getUserUsername());
    }

    @Test
    @DisplayName("Create Account: User not found throws ResourceNotFoundException")
    void testCreateAccount_UserNotFound() {
        AccountRequest request = new AccountRequest();
        request.setAccountType("SAVING");

        when(userRepository.findByUsername("unknown_user")).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () ->
                accountService.createAccount("unknown_user", request));
    }

    @Test
    @DisplayName("Get Account Details: Returns valid details for existing account")
    void testGetAccountDetails_Success() {
        when(accountRepository.findByAccountNumber("1002003004")).thenReturn(Optional.of(sampleAccount));

        AccountResponse response = accountService.getAccountDetails("1002003004");

        assertNotNull(response);
        assertEquals("1002003004", response.getAccountNumber());
        assertEquals(new BigDecimal("25000.00"), response.getBalance());
        assertEquals("priya_patel", response.getUserUsername());
    }

    @Test
    @DisplayName("Get Account Details: Non-existent account throws ResourceNotFoundException")
    void testGetAccountDetails_NotFound() {
        when(accountRepository.findByAccountNumber("9999999999")).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () ->
                accountService.getAccountDetails("9999999999"));
    }

    @Test
    @DisplayName("Freeze Account: Authorized user successfully freezes account")
    void testFreezeAccount_Success() {
        when(accountRepository.findByAccountNumber("1002003004")).thenReturn(Optional.of(sampleAccount));
        when(accountRepository.save(any(Account.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AccountResponse response = accountService.freezeAccount("1002003004", "priya_patel");

        assertEquals(AccountStatus.FROZEN.name(), response.getStatus());
        verify(accountRepository, times(1)).save(sampleAccount);
        verify(auditService, times(1)).log(eq("priya_patel"), eq("FREEZE_ACCOUNT"), anyString(), (String) isNull(), eq("SUCCESS"));
    }

    @Test
    @DisplayName("Freeze Account: Unauthorized user trying to freeze account throws SecurityException")
    void testFreezeAccount_UnauthorizedAccess() {
        when(accountRepository.findByAccountNumber("1002003004")).thenReturn(Optional.of(sampleAccount));

        SecurityException ex = assertThrows(SecurityException.class, () ->
                accountService.freezeAccount("1002003004", "intruder_user"));

        assertTrue(ex.getMessage().contains("Unauthorized access"));
        verify(accountRepository, never()).save(any(Account.class));
    }

    @Test
    @DisplayName("Unfreeze Account: Authorized user successfully restores active status")
    void testUnfreezeAccount_Success() {
        sampleAccount.setStatus(AccountStatus.FROZEN);
        when(accountRepository.findByAccountNumber("1002003004")).thenReturn(Optional.of(sampleAccount));
        when(accountRepository.save(any(Account.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AccountResponse response = accountService.unfreezeAccount("1002003004", "priya_patel");

        assertEquals(AccountStatus.ACTIVE.name(), response.getStatus());
        verify(accountRepository, times(1)).save(sampleAccount);
    }

    @Test
    @DisplayName("Set Transaction PIN: Valid 4-6 digit numeric PIN encoded and saved")
    void testSetTransactionPin_Success() {
        when(accountRepository.findByAccountNumber("1002003004")).thenReturn(Optional.of(sampleAccount));
        when(passwordEncoder.encode("4321")).thenReturn("encoded_pin_4321");

        accountService.setTransactionPin("1002003004", "4321", "priya_patel");

        assertEquals("encoded_pin_4321", sampleAccount.getTransactionPin());
        verify(accountRepository, times(1)).save(sampleAccount);
    }

    @Test
    @DisplayName("Set Transaction PIN: Non-numeric or invalid length PIN rejected")
    void testSetTransactionPin_InvalidFormat() {
        when(accountRepository.findByAccountNumber("1002003004")).thenReturn(Optional.of(sampleAccount));

        // Less than 4 digits
        assertThrows(IllegalArgumentException.class, () ->
                accountService.setTransactionPin("1002003004", "12", "priya_patel"));

        // Non-numeric
        assertThrows(IllegalArgumentException.class, () ->
                accountService.setTransactionPin("1002003004", "abcd", "priya_patel"));

        // Greater than 6 digits
        assertThrows(IllegalArgumentException.class, () ->
                accountService.setTransactionPin("1002003004", "1234567", "priya_patel"));
    }

    @Test
    @DisplayName("Set Transaction PIN: Unauthorized user access throws SecurityException")
    void testSetTransactionPin_UnauthorizedAccess() {
        when(accountRepository.findByAccountNumber("1002003004")).thenReturn(Optional.of(sampleAccount));

        SecurityException ex = assertThrows(SecurityException.class, () ->
                accountService.setTransactionPin("1002003004", "1234", "malicious_user"));

        assertTrue(ex.getMessage().contains("Unauthorized access"));
    }

    @Test
    @DisplayName("Update Limits: Successfully updates per-transaction and daily limits")
    void testUpdateLimits_Success() {
        when(accountRepository.findByAccountNumber("1002003004")).thenReturn(Optional.of(sampleAccount));

        accountService.updateLimits("1002003004", new BigDecimal("25000.00"), new BigDecimal("75000.00"), "priya_patel");

        assertEquals(new BigDecimal("25000.00"), sampleAccount.getPerTransactionLimit());
        assertEquals(new BigDecimal("75000.00"), sampleAccount.getDailyLimit());
        verify(accountRepository, times(1)).save(sampleAccount);
    }

    @Test
    @DisplayName("Update Limits: Unauthorized user updating limits throws SecurityException")
    void testUpdateLimits_UnauthorizedAccess() {
        when(accountRepository.findByAccountNumber("1002003004")).thenReturn(Optional.of(sampleAccount));

        SecurityException ex = assertThrows(SecurityException.class, () ->
                accountService.updateLimits("1002003004", new BigDecimal("10000.00"), new BigDecimal("20000.00"), "other_user"));

        assertTrue(ex.getMessage().contains("Unauthorized access"));
        verify(accountRepository, never()).save(any(Account.class));
    }
}
