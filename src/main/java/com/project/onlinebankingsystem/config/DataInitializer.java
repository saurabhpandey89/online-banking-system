package com.project.onlinebankingsystem.config;

import com.project.onlinebankingsystem.enums.AccountStatus;
import com.project.onlinebankingsystem.enums.AccountType;
import com.project.onlinebankingsystem.enums.Gender;
import com.project.onlinebankingsystem.enums.Role;
import com.project.onlinebankingsystem.model.Account;
import com.project.onlinebankingsystem.model.User;
import com.project.onlinebankingsystem.repository.AccountRepository;
import com.project.onlinebankingsystem.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Random;

/**
 * Ensures Administrator accounts for both 'saurabh' (Saurabh Pandey) and 'admin' (System Administrator)
 * are always initialized, verified, unlocked, and have credentials matching 'admin123'.
 */
@Slf4j
@Component
@Order(10)
@RequiredArgsConstructor
public class DataInitializer implements CommandLineRunner {

    private final UserRepository userRepository;
    private final AccountRepository accountRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    @Transactional
    public void run(String... args) {
        log.info("Verifying administrator configuration for Apex Horizon Bank...");

        try {
            // 1. Ensure Saurabh Pandey admin account
            ensureAdminAccount(
                    "saurabh",
                    "Saurabh Pandey",
                    "saurabh.pandey@apexbank.com",
                    "9876543299",
                    "SAURP1234P",
                    "987612345678"
            );

            // 2. Ensure generic 'admin' account
            ensureAdminAccount(
                    "admin",
                    "System Administrator",
                    "admin@apexbank.com",
                    "9999999999",
                    "ADMIN1234A",
                    "999988887777"
            );

            // 3. Ensure legacy 'shubham' account is also unlocked and has admin123 if present
            userRepository.findByUsername("shubham").ifPresent(u -> {
                u.setRole(Role.ROLE_ADMIN);
                u.setPassword(passwordEncoder.encode("admin123"));
                u.setLockoutUntil(null);
                u.setFailedLoginAttempts(0);
                u.setEmailVerified(true);
                u.setPhoneVerified(true);
                userRepository.save(u);
                log.info("Updated legacy user 'shubham' with ROLE_ADMIN and password 'admin123'.");
            });

        } catch (Exception e) {
            log.error("Error during administrator initialization: {}", e.getMessage(), e);
        }
    }

    private void ensureAdminAccount(String username, String fullName, String email, String phone, String pan, String aadhar) {
        try {
            Optional<User> existingUserOpt = userRepository.findByUsername(username)
                    .or(() -> userRepository.findByIdentifier(fullName))
                    .or(() -> userRepository.findByEmail(email));

            if (existingUserOpt.isPresent()) {
                User user = existingUserOpt.get();
                user.setUsername(username);
                user.setRole(Role.ROLE_ADMIN);
                user.setPassword(passwordEncoder.encode("admin123"));
                user.setLockoutUntil(null);
                user.setFailedLoginAttempts(0);
                user.setEmailVerified(true);
                user.setPhoneVerified(true);

                if (user.getAccounts() == null || user.getAccounts().isEmpty()) {
                    createDefaultAccountForUser(user);
                }

                userRepository.save(user);
                log.info("Administrator '{}' is active with ROLE_ADMIN and password set to 'admin123'.", username);
            } else {
                // Ensure unique constraints don't collide
                String finalEmail = email;
                String finalPhone = phone;
                String finalPan = pan;
                String finalAadhar = aadhar;

                if (userRepository.existsByEmail(finalEmail)) {
                    finalEmail = username + "." + (System.currentTimeMillis() % 1000) + "@apexbank.com";
                }
                if (userRepository.existsByPhone(finalPhone)) {
                    finalPhone = "98765" + (10000 + new Random().nextInt(90000));
                }
                if (userRepository.existsByPan(finalPan)) {
                    finalPan = "ADM" + (10000 + new Random().nextInt(90000)) + "P";
                }
                if (userRepository.existsByAadhar(finalAadhar)) {
                    finalAadhar = "9876" + (10000000L + (long)(new Random().nextDouble() * 90000000L));
                }

                User adminUser = User.builder()
                        .username(username)
                        .password(passwordEncoder.encode("admin123"))
                        .fullName(fullName)
                        .email(finalEmail)
                        .phone(finalPhone)
                        .pan(finalPan)
                        .aadhar(finalAadhar)
                        .gender(Gender.MALE)
                        .dob(LocalDate.of(1995, 8, 15))
                        .address("Apex Horizon Bank Headquarters, Mumbai")
                        .role(Role.ROLE_ADMIN)
                        .emailVerified(true)
                        .phoneVerified(true)
                        .twoFactorEnabled(false)
                        .failedLoginAttempts(0)
                        .build();

                User savedAdmin = userRepository.save(adminUser);
                createDefaultAccountForUser(savedAdmin);
                log.info("Created new Administrator account '{}' ({}) with password 'admin123'.", username, fullName);
            }
        } catch (Exception e) {
            log.error("Failed to ensure admin account for '{}': {}", username, e.getMessage(), e);
        }
    }

    private void createDefaultAccountForUser(User user) {
        Random random = new Random();
        String accountNumber;
        do {
            accountNumber = String.valueOf(1000000000L + (long)(random.nextDouble() * 9000000000L));
        } while (accountRepository.existsByAccountNumber(accountNumber));

        String baseUpi = user.getUsername().replaceAll("[^a-zA-Z0-9]", "").toLowerCase();
        String upiId = baseUpi + "@obsbank";
        int counter = 1;
        while (accountRepository.findByUpiId(upiId).isPresent()) {
            upiId = baseUpi + counter + "@obsbank";
            counter++;
        }

        Account account = Account.builder()
                .accountNumber(accountNumber)
                .upiId(upiId)
                .ifscCode("OBIN0000101")
                .accountType(AccountType.SAVING)
                .balance(new BigDecimal("100000.00"))
                .status(AccountStatus.ACTIVE)
                .transactionPin(passwordEncoder.encode("1234"))
                .perTransactionLimit(new BigDecimal("100000.00"))
                .dailyLimit(new BigDecimal("500000.00"))
                .createdAt(LocalDateTime.now())
                .user(user)
                .build();

        accountRepository.save(account);
    }
}
