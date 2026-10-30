package com.project.onlinebankingsystem.service;

import com.project.onlinebankingsystem.dto.RegisterRequest;
import com.project.onlinebankingsystem.dto.RegisterResponse;
import com.project.onlinebankingsystem.enums.AccountStatus;
import com.project.onlinebankingsystem.enums.AccountType;
import com.project.onlinebankingsystem.enums.Role;
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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private AuditService auditService;

    @Mock
    private NotificationService notificationService;

    @InjectMocks
    private UserService userService;

    private RegisterRequest sampleRequest;

    @BeforeEach
    void setUp() {
        sampleRequest = new RegisterRequest();
        sampleRequest.setUsername("rahul_sharma");
        sampleRequest.setPassword("Secret@123");
        sampleRequest.setFullName("Rahul Sharma");
        sampleRequest.setEmail("rahul.sharma@example.com");
        sampleRequest.setPhone("9876543210");
        sampleRequest.setPan("ABCDE1234F");
        sampleRequest.setAadhar("123456789012");
        sampleRequest.setGender("MALE");
        sampleRequest.setDob(LocalDate.of(1995, 5, 20));
        sampleRequest.setAddress("42, MG Road, Bengaluru");
        sampleRequest.setAccountType("SAVING");
        sampleRequest.setInitialBalance(new BigDecimal("10000.00"));
    }

    @Test
    @DisplayName("User Registration: Success creates user and primary account")
    void testRegisterUser_Success() {
        when(userRepository.existsByUsername("rahul_sharma")).thenReturn(false);
        when(userRepository.existsByEmail("rahul.sharma@example.com")).thenReturn(false);
        when(userRepository.existsByPhone("9876543210")).thenReturn(false);
        when(userRepository.existsByPan("ABCDE1234F")).thenReturn(false);
        when(userRepository.existsByAadhar("123456789012")).thenReturn(false);
        when(passwordEncoder.encode(anyString())).thenReturn("hashed_password");

        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User u = invocation.getArgument(0);
            u.setId(101L);
            return u;
        });

        when(accountRepository.existsByAccountNumber(anyString())).thenReturn(false);
        when(accountRepository.save(any(Account.class))).thenAnswer(invocation -> invocation.getArgument(0));

        RegisterResponse response = userService.registerUser(sampleRequest);

        assertNotNull(response);
        assertEquals("rahul_sharma", response.getUsername());
        assertNotNull(response.getAccountNumber());
        assertEquals("User registered successfully", response.getMessage());

        verify(userRepository, times(1)).save(any(User.class));
        verify(accountRepository, times(1)).save(any(Account.class));
    }

    @Test
    @DisplayName("User Registration: Duplicate Username throws IllegalArgumentException")
    void testRegisterUser_DuplicateUsername() {
        when(userRepository.existsByUsername("rahul_sharma")).thenReturn(true);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                userService.registerUser(sampleRequest));

        assertEquals("Username is already taken", ex.getMessage());
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    @DisplayName("User Registration: Duplicate Email throws IllegalArgumentException")
    void testRegisterUser_DuplicateEmail() {
        when(userRepository.existsByUsername("rahul_sharma")).thenReturn(false);
        when(userRepository.existsByEmail("rahul.sharma@example.com")).thenReturn(true);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                userService.registerUser(sampleRequest));

        assertEquals("Email is already registered", ex.getMessage());
    }

    @Test
    @DisplayName("User Registration: Duplicate Phone throws IllegalArgumentException")
    void testRegisterUser_DuplicatePhone() {
        when(userRepository.existsByUsername("rahul_sharma")).thenReturn(false);
        when(userRepository.existsByEmail("rahul.sharma@example.com")).thenReturn(false);
        when(userRepository.existsByPhone("9876543210")).thenReturn(true);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                userService.registerUser(sampleRequest));

        assertEquals("Phone number is already registered", ex.getMessage());
    }

    @Test
    @DisplayName("User Registration: Duplicate PAN Card throws IllegalArgumentException")
    void testRegisterUser_DuplicatePan() {
        when(userRepository.existsByUsername("rahul_sharma")).thenReturn(false);
        when(userRepository.existsByEmail("rahul.sharma@example.com")).thenReturn(false);
        when(userRepository.existsByPhone("9876543210")).thenReturn(false);
        when(userRepository.existsByPan("ABCDE1234F")).thenReturn(true);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                userService.registerUser(sampleRequest));

        assertEquals("PAN Card is already registered", ex.getMessage());
    }

    @Test
    @DisplayName("User Registration: Duplicate Aadhar Card throws IllegalArgumentException")
    void testRegisterUser_DuplicateAadhar() {
        when(userRepository.existsByUsername("rahul_sharma")).thenReturn(false);
        when(userRepository.existsByEmail("rahul.sharma@example.com")).thenReturn(false);
        when(userRepository.existsByPhone("9876543210")).thenReturn(false);
        when(userRepository.existsByPan("ABCDE1234F")).thenReturn(false);
        when(userRepository.existsByAadhar("123456789012")).thenReturn(true);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                userService.registerUser(sampleRequest));

        assertEquals("Aadhar Card is already registered", ex.getMessage());
    }

    @Test
    @DisplayName("Password Change: Successful change with valid credentials")
    void testChangePassword_Success() {
        User user = User.builder()
                .username("rahul_sharma")
                .password("encoded_old")
                .build();

        when(userRepository.findByUsername("rahul_sharma")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("OldPass@123", "encoded_old")).thenReturn(true);
        when(passwordEncoder.encode("NewPass@456")).thenReturn("encoded_new");

        userService.changePassword("rahul_sharma", "OldPass@123", "NewPass@456");

        assertEquals("encoded_new", user.getPassword());
        verify(userRepository, times(1)).save(user);
    }

    @Test
    @DisplayName("Password Change: Incorrect current password rejected")
    void testChangePassword_IncorrectCurrentPassword() {
        User user = User.builder()
                .username("rahul_sharma")
                .password("encoded_old")
                .build();

        when(userRepository.findByUsername("rahul_sharma")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("WrongPass", "encoded_old")).thenReturn(false);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                userService.changePassword("rahul_sharma", "WrongPass", "NewPass@456"));

        assertEquals("Current password is incorrect.", ex.getMessage());
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    @DisplayName("Password Change: Short password (<6 chars) rejected")
    void testChangePassword_ShortPassword() {
        User user = User.builder()
                .username("rahul_sharma")
                .password("encoded_old")
                .build();

        when(userRepository.findByUsername("rahul_sharma")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("OldPass@123", "encoded_old")).thenReturn(true);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                userService.changePassword("rahul_sharma", "OldPass@123", "123"));

        assertEquals("New password must be at least 6 characters long.", ex.getMessage());
    }

    @Test
    @DisplayName("Password Reset via OTP: Successful verification and password update")
    void testPasswordResetWithOtp_Success() {
        User user = User.builder()
                .username("rahul_sharma")
                .otpCode("123456")
                .otpExpiry(LocalDateTime.now().plusMinutes(10))
                .failedLoginAttempts(3)
                .build();

        when(userRepository.findByIdentifier("rahul_sharma")).thenReturn(Optional.of(user));
        when(passwordEncoder.encode("BrandNewPassword@123")).thenReturn("encoded_brand_new");

        userService.resetPasswordWithOtp("rahul_sharma", "123456", "BrandNewPassword@123");

        assertEquals("encoded_brand_new", user.getPassword());
        assertNull(user.getOtpCode());
        assertNull(user.getOtpExpiry());
        assertEquals(0, user.getFailedLoginAttempts());
        verify(userRepository, times(1)).save(user);
    }

    @Test
    @DisplayName("Password Reset via OTP: Invalid OTP rejected")
    void testPasswordResetWithOtp_InvalidOtp() {
        User user = User.builder()
                .username("rahul_sharma")
                .otpCode("123456")
                .otpExpiry(LocalDateTime.now().plusMinutes(10))
                .build();

        when(userRepository.findByIdentifier("rahul_sharma")).thenReturn(Optional.of(user));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                userService.resetPasswordWithOtp("rahul_sharma", "999999", "BrandNewPassword@123"));

        assertEquals("Invalid or expired OTP code.", ex.getMessage());
    }

    @Test
    @DisplayName("Password Reset via OTP: Expired OTP rejected")
    void testPasswordResetWithOtp_ExpiredOtp() {
        User user = User.builder()
                .username("rahul_sharma")
                .otpCode("123456")
                .otpExpiry(LocalDateTime.now().minusMinutes(5)) // Expired
                .build();

        when(userRepository.findByIdentifier("rahul_sharma")).thenReturn(Optional.of(user));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                userService.resetPasswordWithOtp("rahul_sharma", "123456", "BrandNewPassword@123"));

        assertTrue(ex.getMessage().contains("OTP code has expired"));
    }
}
