package com.project.onlinebankingsystem.controller;

import com.project.onlinebankingsystem.dto.*;
import com.project.onlinebankingsystem.enums.AccountStatus;
import com.project.onlinebankingsystem.enums.Role;
import com.project.onlinebankingsystem.exception.InsufficientBalanceException;
import com.project.onlinebankingsystem.exception.ResourceNotFoundException;
import com.project.onlinebankingsystem.model.Account;
import com.project.onlinebankingsystem.model.AuditLog;
import com.project.onlinebankingsystem.model.Notification;
import com.project.onlinebankingsystem.model.User;
import com.project.onlinebankingsystem.repository.AccountRepository;
import com.project.onlinebankingsystem.repository.UserRepository;
import com.project.onlinebankingsystem.service.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.security.Principal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * ============================================================================
 *                 VIEW CONTROLLER (Web Pages Routing)
 * ============================================================================
 * Connects the web browser HTML templates (Thymeleaf) with backend services.
 * 
 * CORE WEB ROUTES:
 * - GET  /              -> Home landing page (index.html)
 * - GET  /login         -> Login page (login.html)
 * - POST /register      -> Handles new user registration (register.html)
 * - GET  /dashboard     -> Main banking dashboard (dashboard.html)
 * - POST /transactions/deposit  -> Cash deposit form submission
 * - POST /transactions/withdraw -> Cash withdrawal form submission
 * - POST /transactions/transfer -> Inter-account fund transfer
 * - GET  /admin         -> Administrator management console (admin.html)
 * ============================================================================
 */
@Controller
public class ViewController {

    private final UserService userService;
    private final AccountService accountService;
    private final TransactionService transactionService;
    private final BeneficiaryService beneficiaryService;
    private final UserRepository userRepository;
    private final AccountRepository accountRepository;
    private final AuditService auditService;
    private final NotificationService notificationService;
    private final StatementService statementService;
    private final UpiService upiService;

    public ViewController(UserService userService, AccountService accountService, 
                          TransactionService transactionService, BeneficiaryService beneficiaryService, 
                          UserRepository userRepository, AccountRepository accountRepository,
                          AuditService auditService, NotificationService notificationService,
                          StatementService statementService,
                          UpiService upiService) {
        this.userService = userService;
        this.accountService = accountService;
        this.transactionService = transactionService;
        this.beneficiaryService = beneficiaryService;
        this.userRepository = userRepository;
        this.accountRepository = accountRepository;
        this.auditService = auditService;
        this.notificationService = notificationService;
        this.statementService = statementService;
        this.upiService = upiService;
    }

    private boolean isAuthenticated() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken);
    }

    @GetMapping("/")
    public String index() {
        if (isAuthenticated()) {
            return "redirect:/dashboard";
        }
        return "index";
    }

    @GetMapping("/login")
    public String login() {
        if (isAuthenticated()) {
            return "redirect:/dashboard";
        }
        return "login";
    }

    @GetMapping("/register")
    public String showRegisterForm(Model model) {
        if (isAuthenticated()) {
            return "redirect:/dashboard";
        }
        model.addAttribute("registerRequest", new RegisterRequest());
        return "register";
    }

    @PostMapping("/register")
    public String registerUser(@Valid @ModelAttribute("registerRequest") RegisterRequest registerRequest,
                               BindingResult bindingResult, Model model, RedirectAttributes redirectAttributes) {
        if (bindingResult.hasErrors()) {
            return "register";
        }
        try {
            userService.registerUser(registerRequest);
            redirectAttributes.addFlashAttribute("success", "Registration successful! Please login.");
            return "redirect:/login";
        } catch (IllegalArgumentException e) {
            model.addAttribute("error", e.getMessage());
            return "register";
        }
    }

    // ==========================================
    // FORGOT PASSWORD WORKFLOW (OTP-BASED)
    // ==========================================
    @PostMapping("/forgot-password/send-otp")
    public String sendForgotPasswordOtp(@RequestParam String identifier, RedirectAttributes redirectAttributes) {
        try {
            String otp = userService.generatePasswordResetOtp(identifier);
            redirectAttributes.addFlashAttribute("success", "Verification OTP sent! For demo verification, your code is: " + otp);
            redirectAttributes.addFlashAttribute("resetIdentifier", identifier.trim());
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/login";
    }

    @PostMapping("/forgot-password/reset")
    public String resetPasswordWithOtp(@RequestParam String identifier, @RequestParam String otp,
                                       @RequestParam String newPassword, RedirectAttributes redirectAttributes) {
        try {
            userService.resetPasswordWithOtp(identifier, otp, newPassword);
            redirectAttributes.addFlashAttribute("success", "Password reset successfully! Please sign in with your new password.");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/login";
    }

    // ==========================================
    // MAIN DASHBOARD
    // ==========================================
    @GetMapping("/dashboard")
    public String dashboard(Principal principal, HttpServletRequest request, Model model) {
        User user = userRepository.findByUsername(principal.getName()).orElse(null);
        if (user == null) {
            // Invalidate session and security context if user is deleted or DB restarts
            SecurityContextHolder.clearContext();
            HttpSession session = request.getSession(false);
            if (session != null) {
                session.invalidate();
            }
            return "redirect:/login?error";
        }

        List<AccountResponse> accounts = accountService.getAccountsByUsername(principal.getName());
        List<TransactionResponse> transactions = new ArrayList<>();
        BigDecimal dailySpent = BigDecimal.ZERO;

        if (!accounts.isEmpty()) {
            transactions = transactionService.getTransactionHistory(accounts.get(0).getAccountNumber());
            Account primaryAcc = accountRepository.findByAccountNumber(accounts.get(0).getAccountNumber()).orElse(null);
            if (primaryAcc != null) {
                dailySpent = transactionService.getDailyDebits(primaryAcc);
            }
        }

        List<User> quickContacts = userRepository.findAll().stream()
                .filter(u -> !u.getUsername().equalsIgnoreCase(principal.getName()) && u.getRole() != Role.ROLE_ADMIN)
                .limit(8)
                .toList();

        model.addAttribute("user", user);
        model.addAttribute("accounts", accounts);
        model.addAttribute("transactions", transactions);
        model.addAttribute("dailySpent", dailySpent);
        model.addAttribute("beneficiaries", beneficiaryService.getBeneficiariesByUsername(principal.getName()));
        model.addAttribute("quickContacts", quickContacts);
        model.addAttribute("allUsers", userService.getAllUsers());

        // Notifications & Security Audit
        model.addAttribute("notifications", notificationService.getNotificationsForUser(principal.getName()));
        model.addAttribute("unreadNotifs", notificationService.getUnreadCount(principal.getName()));
        model.addAttribute("userAuditLogs", auditService.getUserAuditLogs(principal.getName()));

        if (user.getRole() == Role.ROLE_ADMIN) {
            model.addAttribute("allAccounts", accountService.getAllAccounts());
        }
        
        return "dashboard";
    }

    // ==========================================
    // SECURITY, PASSWORD & VERIFICATION ENDPOINTS
    // ==========================================
    @PostMapping("/auth/change-password")
    public String changePassword(Principal principal, @RequestParam String currentPassword,
                                 @RequestParam String newPassword, RedirectAttributes redirectAttributes) {
        try {
            userService.changePassword(principal.getName(), currentPassword, newPassword);
            redirectAttributes.addFlashAttribute("success", "Password changed successfully!");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/dashboard";
    }

    @PostMapping("/auth/verify-email")
    public String verifyEmail(Principal principal, @RequestParam(defaultValue = "123456") String otp,
                              RedirectAttributes redirectAttributes) {
        try {
            userService.verifyEmail(principal.getName(), otp);
            redirectAttributes.addFlashAttribute("success", "Email address verified successfully!");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/dashboard";
    }

    @PostMapping("/auth/verify-phone")
    public String verifyPhone(Principal principal, @RequestParam(defaultValue = "123456") String otp,
                              RedirectAttributes redirectAttributes) {
        try {
            userService.verifyPhone(principal.getName(), otp);
            redirectAttributes.addFlashAttribute("success", "Mobile phone number verified successfully!");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/dashboard";
    }

    @PostMapping("/auth/toggle-2fa")
    public String toggle2fa(Principal principal, RedirectAttributes redirectAttributes) {
        try {
            boolean enabled = userService.toggleTwoFactor(principal.getName());
            redirectAttributes.addFlashAttribute("success", "Two-Factor Authentication is now " + (enabled ? "ENABLED" : "DISABLED") + ".");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/dashboard";
    }

    // ==========================================
    // ACCOUNT CONTROLS: FREEZE, PIN, LIMITS & STATEMENT
    // ==========================================
    @PostMapping("/accounts/freeze")
    public String freezeAccount(Principal principal, @RequestParam String accountNumber, RedirectAttributes redirectAttributes) {
        try {
            accountService.freezeAccount(accountNumber, principal.getName());
            redirectAttributes.addFlashAttribute("success", "Account " + accountNumber + " has been FROZEN. Debit transactions are suspended.");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/dashboard";
    }

    @PostMapping("/accounts/unfreeze")
    public String unfreezeAccount(Principal principal, @RequestParam String accountNumber, RedirectAttributes redirectAttributes) {
        try {
            accountService.unfreezeAccount(accountNumber, principal.getName());
            redirectAttributes.addFlashAttribute("success", "Account " + accountNumber + " has been UNFROZEN to ACTIVE.");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/dashboard";
    }

    @PostMapping("/accounts/set-pin")
    public String setTransactionPin(Principal principal, @RequestParam String accountNumber,
                                    @RequestParam String pin, RedirectAttributes redirectAttributes) {
        try {
            accountService.setTransactionPin(accountNumber, pin, principal.getName());
            redirectAttributes.addFlashAttribute("success", "4-digit Transaction PIN set successfully for account " + accountNumber + "!");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/dashboard";
    }

    @PostMapping("/accounts/limits")
    public String updateLimits(Principal principal, @RequestParam String accountNumber,
                               @RequestParam BigDecimal perTransactionLimit,
                               @RequestParam BigDecimal dailyLimit, RedirectAttributes redirectAttributes) {
        try {
            accountService.updateLimits(accountNumber, perTransactionLimit, dailyLimit, principal.getName());
            redirectAttributes.addFlashAttribute("success", "Transaction limits updated successfully!");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/dashboard";
    }

    @GetMapping("/accounts/{accountNumber}/statement")
    public String viewStatement(Principal principal, @PathVariable String accountNumber,
                                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
                                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
                                Model model) {
        StatementService.StatementReport report = statementService.generateStatement(accountNumber, fromDate, toDate);
        if (principal != null && report.account != null && report.account.getUser() != null) {
            if (!report.account.getUser().getUsername().equals(principal.getName())) {
                User currentUser = userRepository.findByUsername(principal.getName()).orElse(null);
                if (currentUser == null || currentUser.getRole() != Role.ROLE_ADMIN) {
                    throw new SecurityException("Unauthorized access to account statement.");
                }
            }
        }
        model.addAttribute("report", report);
        return "statement";
    }

    @PostMapping("/notifications/mark-read")
    public String markNotificationsRead(Principal principal) {
        try {
            notificationService.markAllAsRead(principal.getName());
        } catch (Exception ignored) {}
        return "redirect:/dashboard";
    }

    // ==========================================
    // SERVICES & TRANSFERS (WITH T-PIN SUPPORT)
    // ==========================================
    @PostMapping("/services/pay-mobile")
    public String payMobile(Principal principal, @RequestParam String sourceAccountNumber,
                            @RequestParam String mobileNumber, @RequestParam(required = false) BigDecimal amount,
                            @RequestParam(required = false) String pin,
                            @RequestParam(required = false) String idempotencyKey,
                            @RequestParam(required = false) String notes, RedirectAttributes redirectAttributes) {
        try {
            java.util.Optional<User> recipientUser = userRepository.findByPhone(mobileNumber.trim());
            TransactionResponse resp;
            if (recipientUser.isPresent() && !recipientUser.get().getAccounts().isEmpty()) {
                String destAcc = recipientUser.get().getAccounts().get(0).getAccountNumber();
                resp = transactionService.transfer(sourceAccountNumber, destAcc, amount, "Mobile Pay: " + mobileNumber + (notes != null && !notes.isBlank() ? " (" + notes + ")" : ""), pin, idempotencyKey);
                if (resp.isIdempotentReplay()) {
                    redirectAttributes.addFlashAttribute("info", "⚠️ Payment already processed earlier (Ref: " + resp.getTransactionReference() + "). No duplicate debit occurred.");
                } else {
                    redirectAttributes.addFlashAttribute("success", "Successfully transferred ₹" + amount + " to " + recipientUser.get().getFullName() + " (" + mobileNumber + ")!");
                }
            } else {
                resp = transactionService.withdraw(sourceAccountNumber, amount, "Mobile Payment to: " + mobileNumber + (notes != null && !notes.isBlank() ? " (" + notes + ")" : ""), pin, idempotencyKey);
                if (resp.isIdempotentReplay()) {
                    redirectAttributes.addFlashAttribute("info", "⚠️ Payment already processed earlier (Ref: " + resp.getTransactionReference() + "). No duplicate debit occurred.");
                } else {
                    redirectAttributes.addFlashAttribute("success", "Mobile Payment of ₹" + amount + " sent to " + mobileNumber + " successfully!");
                }
            }
        } catch (InsufficientBalanceException e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/dashboard";
    }

    @PostMapping("/services/pay-upi")
    public String payUpi(Principal principal, @RequestParam String sourceAccountNumber,
                         @RequestParam String upiId, @RequestParam(required = false) BigDecimal amount,
                         @RequestParam(required = false) String pin,
                         @RequestParam(required = false) String idempotencyKey,
                         @RequestParam(required = false) String notes, RedirectAttributes redirectAttributes) {
        try {
            com.project.onlinebankingsystem.dto.UpiPaymentRequest req = com.project.onlinebankingsystem.dto.UpiPaymentRequest.builder()
                    .sourceAccountNumber(sourceAccountNumber)
                    .payeeVpa(upiId.trim())
                    .amount(amount)
                    .pin(pin)
                    .description(notes)
                    .idempotencyKey(idempotencyKey)
                    .build();

            com.project.onlinebankingsystem.dto.UpiPaymentResponse resp = upiService.executeUpiPayment(req, principal != null ? principal.getName() : "USER");
            if ("SUCCESS".equalsIgnoreCase(resp.getStatus())) {
                if (resp.isIdempotentReplay()) {
                    redirectAttributes.addFlashAttribute("info", resp.getMessage() + " (RRN: " + resp.getNpciRrn() + ")");
                } else {
                    redirectAttributes.addFlashAttribute("success", "UPI Payment of ₹" + resp.getFormattedAmount() + " to " + resp.getPayeeVpa() + " successful! (NPCI RRN: " + resp.getNpciRrn() + ")");
                }
            } else {
                redirectAttributes.addFlashAttribute("error", "UPI Payment Failed [" + resp.getResponseCode() + "]: " + resp.getMessage());
            }
        } catch (InsufficientBalanceException e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/dashboard";
    }

    @PostMapping("/services/self-transfer")
    public String selfTransfer(@RequestParam String sourceAccountNumber, @RequestParam String destinationAccountNumber,
                               @RequestParam(required = false) BigDecimal amount, @RequestParam(required = false) String pin,
                               RedirectAttributes redirectAttributes) {
        try {
            if (sourceAccountNumber.equals(destinationAccountNumber)) {
                redirectAttributes.addFlashAttribute("error", "Source and destination accounts cannot be the same!");
                return "redirect:/dashboard";
            }
            transactionService.transfer(sourceAccountNumber, destinationAccountNumber, amount, "Self Account Transfer", pin);
            redirectAttributes.addFlashAttribute("success", "Self transfer completed successfully!");
        } catch (InsufficientBalanceException e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/dashboard";
    }

    @PostMapping("/services/recharge-bill")
    public String rechargeBill(@RequestParam String sourceAccountNumber, @RequestParam String billerType,
                               @RequestParam String billerName, @RequestParam String consumerNumber,
                               @RequestParam(required = false) BigDecimal amount, @RequestParam(required = false) String pin,
                               RedirectAttributes redirectAttributes) {
        try {
            String desc = billerType + " (" + billerName + " - " + consumerNumber + ")";
            transactionService.withdraw(sourceAccountNumber, amount, "Bill Paid: " + desc, pin);
            redirectAttributes.addFlashAttribute("success", "Payment for " + desc + " successful! Receipt generated.");
        } catch (InsufficientBalanceException e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/dashboard";
    }

    @PostMapping("/services/apply-loan")
    public String applyLoan(@RequestParam String destinationAccountNumber, @RequestParam(required = false) BigDecimal loanAmount,
                            @RequestParam int tenureMonths, RedirectAttributes redirectAttributes) {
        try {
            transactionService.deposit(destinationAccountNumber, loanAmount, "Instant Personal Loan Disbursed (" + tenureMonths + "M EMI)");
            redirectAttributes.addFlashAttribute("success", "🎉 Congratulations! Instant Loan of ₹" + loanAmount + " has been approved & disbursed into your account!");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", "Loan application error: " + e.getMessage());
        }
        return "redirect:/dashboard";
    }

    @PostMapping("/services/book-fd")
    public String bookFd(@RequestParam String sourceAccountNumber, @RequestParam(required = false) BigDecimal fdAmount,
                         @RequestParam int tenureYears, @RequestParam double interestRate,
                         @RequestParam(required = false) String pin,
                         RedirectAttributes redirectAttributes) {
        try {
            transactionService.withdraw(sourceAccountNumber, fdAmount, "Fixed Deposit Booking (" + tenureYears + " Yr @ " + interestRate + "% p.a.)", pin);
            redirectAttributes.addFlashAttribute("success", "💎 Fixed Deposit booked successfully at " + interestRate + "% p.a. for " + tenureYears + " Year(s)!");
        } catch (InsufficientBalanceException e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/dashboard";
    }

    @PostMapping("/services/claim-cashback")
    public String claimCashback(@RequestParam String destinationAccountNumber, @RequestParam(required = false) BigDecimal amount,
                                RedirectAttributes redirectAttributes) {
        try {
            if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
                amount = new BigDecimal("45.00");
            }
            transactionService.deposit(destinationAccountNumber, amount, "Cashback Reward Credited 🎁");
            redirectAttributes.addFlashAttribute("success", "🎉 Congratulations! ₹" + amount + " Cashback Reward has been credited to your bank account!");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", "Failed to claim cashback: " + e.getMessage());
        }
        return "redirect:/dashboard";
    }

    @PostMapping("/services/upi-lite-topup")
    public String upiLiteTopup(@RequestParam String sourceAccountNumber, @RequestParam(required = false) BigDecimal amount,
                               @RequestParam(required = false) String pin,
                               RedirectAttributes redirectAttributes) {
        try {
            transactionService.withdraw(sourceAccountNumber, amount, "UPI Lite Wallet Top-Up", pin);
            redirectAttributes.addFlashAttribute("success", "⚡ UPI Lite topped up successfully! PIN-free micro payments enabled.");
        } catch (InsufficientBalanceException e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/dashboard";
    }

    @PostMapping("/accounts/create")
    public String createAccount(Principal principal, @RequestParam String accountType, RedirectAttributes redirectAttributes) {
        try {
            AccountRequest request = new AccountRequest(accountType);
            accountService.createAccount(principal.getName(), request);
            redirectAttributes.addFlashAttribute("success", "New account created successfully!");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/dashboard";
    }

    @PostMapping("/transactions/deposit")
    public String deposit(@RequestParam(required = false) String accountNumber,
                          @RequestParam(required = false) BigDecimal amount,
                          @RequestParam(required = false) String idempotencyKey,
                          RedirectAttributes redirectAttributes) {
        try {
            TransactionResponse resp = transactionService.deposit(accountNumber, amount, "ATM Cash Deposit", idempotencyKey);
            if (resp.isIdempotentReplay()) {
                redirectAttributes.addFlashAttribute("info", "⚠️ Deposit already processed earlier (Ref: " + resp.getTransactionReference() + "). No duplicate deposit occurred.");
            } else {
                redirectAttributes.addFlashAttribute("success", "Cash deposited successfully! (Ref: " + resp.getTransactionReference() + ")");
            }
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/dashboard";
    }

    @PostMapping("/transactions/withdraw")
    public String withdraw(@RequestParam(required = false) String accountNumber,
                           @RequestParam(required = false) BigDecimal amount,
                           @RequestParam(required = false) String pin,
                           @RequestParam(required = false) String idempotencyKey,
                           RedirectAttributes redirectAttributes) {
        try {
            TransactionResponse resp = transactionService.withdraw(accountNumber, amount, "ATM Cash Withdrawal", pin, idempotencyKey);
            if (resp.isIdempotentReplay()) {
                redirectAttributes.addFlashAttribute("info", "⚠️ Withdrawal already processed earlier (Ref: " + resp.getTransactionReference() + "). No duplicate deduction occurred.");
            } else {
                redirectAttributes.addFlashAttribute("success", "Cash withdrawn successfully! (Ref: " + resp.getTransactionReference() + ")");
            }
        } catch (InsufficientBalanceException e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/dashboard";
    }

    @PostMapping("/transactions/transfer")
    public String transfer(@RequestParam(required = false) String sourceAccountNumber,
                           @RequestParam(required = false) String destinationAccountNumber, 
                           @RequestParam(required = false) BigDecimal amount,
                           @RequestParam(required = false) String description, 
                           @RequestParam(required = false) String pin,
                           @RequestParam(required = false) String idempotencyKey,
                           RedirectAttributes redirectAttributes) {
        try {
            TransactionResponse resp = transactionService.transfer(sourceAccountNumber, destinationAccountNumber, amount, description, pin, idempotencyKey);
            if (resp.isIdempotentReplay()) {
                redirectAttributes.addFlashAttribute("info", "⚠️ Transfer already processed earlier (Ref: " + resp.getTransactionReference() + "). Your account was NOT debited again.");
            } else {
                redirectAttributes.addFlashAttribute("success", "Fund transfer completed successfully! (Ref: " + resp.getTransactionReference() + ")");
            }
        } catch (InsufficientBalanceException e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/dashboard";
    }

    @PostMapping("/beneficiaries/create")
    public String addBeneficiary(Principal principal, @RequestParam String name, @RequestParam String accountNumber,
                                 @RequestParam String ifscCode, @RequestParam(required = false) String bankName, 
                                 RedirectAttributes redirectAttributes) {
        try {
            BeneficiaryRequest request = new BeneficiaryRequest(name, accountNumber, ifscCode, bankName);
            beneficiaryService.addBeneficiary(principal.getName(), request);
            redirectAttributes.addFlashAttribute("success", "Beneficiary added successfully! Standard 15m security cooling applies, or verify with instant OTP.");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/dashboard";
    }

    @PostMapping("/beneficiaries/verify")
    public String verifyBeneficiary(Principal principal, @RequestParam Long beneficiaryId,
                                    @RequestParam(defaultValue = "123456") String otp, RedirectAttributes redirectAttributes) {
        try {
            beneficiaryService.verifyBeneficiary(beneficiaryId, principal.getName(), otp);
            redirectAttributes.addFlashAttribute("success", "Beneficiary verified successfully! Activated for transfers.");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/dashboard";
    }

    @PostMapping("/beneficiaries/delete/{id}")
    public String deleteBeneficiary(Principal principal, @PathVariable Long id, RedirectAttributes redirectAttributes) {
        try {
            beneficiaryService.deleteBeneficiary(id, principal.getName());
            redirectAttributes.addFlashAttribute("success", "Beneficiary deleted successfully!");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/dashboard";
    }

    // ==========================================
    // ADMIN PANEL & CONTROLS
    // ==========================================
    @GetMapping("/admin")
    public String adminPanel(Principal principal, Model model) {
        if (principal == null) {
            return "redirect:/login";
        }
        User currentUser = userRepository.findByUsername(principal.getName()).orElse(null);
        if (currentUser == null || currentUser.getRole() != Role.ROLE_ADMIN) {
            return "redirect:/dashboard";
        }

        List<User> users = userService.getAllUsers();
        List<AccountResponse> accounts = accountService.getAllAccounts();
        List<TransactionResponse> transactions = transactionService.getAllTransactions();
        List<TransactionResponse> failedTransactions = transactionService.getFailedTransactions();
        List<AuditLog> auditLogs = auditService.getAllAuditLogs();

        BigDecimal totalLiquidity = accounts.stream()
                .map(AccountResponse::getBalance)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        model.addAttribute("users", users);
        model.addAttribute("accounts", accounts);
        model.addAttribute("transactions", transactions);
        model.addAttribute("failedTransactions", failedTransactions);
        model.addAttribute("auditLogs", auditLogs);
        model.addAttribute("totalLiquidity", totalLiquidity);

        return "admin";
    }

    @PostMapping("/admin/accounts/update-status")
    public String updateAccountStatus(@RequestParam String accountNumber, @RequestParam String status, RedirectAttributes redirectAttributes) {
        try {
            Account account = accountRepository.findByAccountNumber(accountNumber)
                    .orElseThrow(() -> new ResourceNotFoundException("Account not found"));
            account.setStatus(AccountStatus.valueOf(status.toUpperCase()));
            accountRepository.save(account);
            redirectAttributes.addFlashAttribute("success", "Account status updated to " + status + "!");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin";
    }

    @PostMapping("/admin/transactions/reverse/{ref}")
    public String reverseTransaction(Principal principal, @PathVariable String ref, RedirectAttributes redirectAttributes) {
        try {
            transactionService.reverseTransaction(ref, principal.getName());
            redirectAttributes.addFlashAttribute("success", "Transaction " + ref + " reversed and refunded successfully!");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", "Reversal failed: " + e.getMessage());
        }
        return "redirect:/admin";
    }

    @PostMapping("/transactions/cancel/{ref}")
    public String cancelUserTransaction(Principal principal, @PathVariable String ref,
                                        @RequestParam(required = false, defaultValue = "Cancelled by user") String reason,
                                        RedirectAttributes redirectAttributes) {
        try {
            transactionService.cancelTransaction(ref, reason, principal != null ? principal.getName() : "USER");
            redirectAttributes.addFlashAttribute("success", "Pending transaction " + ref + " was cancelled successfully.");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", "Failed to cancel transaction: " + e.getMessage());
        }
        return "redirect:/dashboard";
    }

    @PostMapping("/admin/transactions/approve/{ref}")
    public String approveTransaction(Principal principal, @PathVariable String ref, RedirectAttributes redirectAttributes) {
        try {
            transactionService.approveTransaction(ref, principal != null ? principal.getName() : "ADMIN");
            redirectAttributes.addFlashAttribute("success", "Transaction " + ref + " approved and executed successfully!");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", "Approval failed: " + e.getMessage());
        }
        return "redirect:/admin";
    }

    @PostMapping("/admin/transactions/cancel/{ref}")
    public String adminCancelTransaction(Principal principal, @PathVariable String ref,
                                         @RequestParam(required = false, defaultValue = "Cancelled by compliance officer") String reason,
                                         RedirectAttributes redirectAttributes) {
        try {
            transactionService.cancelTransaction(ref, reason, principal != null ? principal.getName() : "ADMIN");
            redirectAttributes.addFlashAttribute("success", "Transaction " + ref + " cancelled.");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", "Cancellation failed: " + e.getMessage());
        }
        return "redirect:/admin";
    }

    @PostMapping("/admin/users/update-role")
    public String updateUserRole(Principal principal, @RequestParam String username, @RequestParam String role, RedirectAttributes redirectAttributes) {
        try {
            userService.updateUserRole(username, Role.valueOf(role.toUpperCase()));
            redirectAttributes.addFlashAttribute("success", "User " + username + " role updated to " + role + " successfully!");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", "Failed to update role: " + e.getMessage());
        }
        return "redirect:/admin";
    }

    @PostMapping("/admin/users/promote")
    public String promoteUserToAdmin(Principal principal, @RequestParam String identifier, RedirectAttributes redirectAttributes) {
        try {
            User user = userService.promoteToAdmin(identifier);
            redirectAttributes.addFlashAttribute("success", "User " + user.getFullName() + " (" + user.getUsername() + ") has been promoted to ROLE_ADMIN!");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", "Promotion failed: " + e.getMessage());
        }
        return "redirect:/admin";
    }

    @GetMapping("/favicon.ico")
    @ResponseBody
    public void returnNoFavicon() {
        // Return empty to silence favicon warning
    }
}
