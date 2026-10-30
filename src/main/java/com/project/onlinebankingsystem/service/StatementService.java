package com.project.onlinebankingsystem.service;

import com.project.onlinebankingsystem.model.Account;
import com.project.onlinebankingsystem.model.Transaction;
import com.project.onlinebankingsystem.model.User;
import com.project.onlinebankingsystem.repository.AccountRepository;
import com.project.onlinebankingsystem.repository.TransactionRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@Service
public class StatementService {

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;

    public StatementService(AccountRepository accountRepository, TransactionRepository transactionRepository) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
    }

    public static class StatementItem {
        public String date;
        public String reference;
        public String description;
        public String type;
        public BigDecimal debit;
        public BigDecimal credit;
        public BigDecimal runningBalance;
        public String status;
    }

    public static class StatementReport {
        public Account account;
        public User user;
        public String fromDate;
        public String toDate;
        public BigDecimal openingBalance;
        public BigDecimal closingBalance;
        public BigDecimal totalCredits;
        public BigDecimal totalDebits;
        public List<StatementItem> items = new ArrayList<>();
    }

    public StatementReport generateStatement(String accountNumber, LocalDate from, LocalDate to) {
        Account account = accountRepository.findByAccountNumber(accountNumber)
                .orElseThrow(() -> new IllegalArgumentException("Account not found: " + accountNumber));

        if (from == null) from = LocalDate.now().minusMonths(1);
        if (to == null) to = LocalDate.now();

        LocalDateTime start = from.atStartOfDay();
        LocalDateTime end = to.atTime(LocalTime.MAX);

        List<Transaction> transactions = transactionRepository.findByAccountAndDateRange(account, start, end);

        StatementReport report = new StatementReport();
        report.account = account;
        report.user = account.getUser();
        report.fromDate = from.format(DateTimeFormatter.ofPattern("dd-MMM-yyyy"));
        report.toDate = to.format(DateTimeFormatter.ofPattern("dd-MMM-yyyy"));

        // Approximate opening balance from current balance and period transactions
        BigDecimal currentBalance = account.getBalance();
        BigDecimal periodCredits = BigDecimal.ZERO;
        BigDecimal periodDebits = BigDecimal.ZERO;

        for (Transaction tx : transactions) {
            if ("SUCCESS".equalsIgnoreCase(tx.getStatus().name())) {
                boolean isCredit = tx.getDestinationAccount() != null &&
                        tx.getDestinationAccount().getAccountNumber().equals(accountNumber);
                if (isCredit) {
                    periodCredits = periodCredits.add(tx.getAmount());
                } else {
                    periodDebits = periodDebits.add(tx.getAmount());
                }
            }
        }

        BigDecimal openingBalance = currentBalance.subtract(periodCredits).add(periodDebits);
        if (openingBalance.compareTo(BigDecimal.ZERO) < 0) openingBalance = BigDecimal.ZERO;

        report.openingBalance = openingBalance;
        report.totalCredits = periodCredits;
        report.totalDebits = periodDebits;
        report.closingBalance = currentBalance;

        BigDecimal running = openingBalance;
        DateTimeFormatter dtf = DateTimeFormatter.ofPattern("dd-MMM-yyyy HH:mm");

        for (Transaction tx : transactions) {
            StatementItem item = new StatementItem();
            item.date = tx.getTimestamp().format(dtf);
            item.reference = tx.getTransactionReference();
            item.description = tx.getDescription() != null ? tx.getDescription() : tx.getType().name();
            item.status = tx.getStatus().name();

            boolean isCredit = tx.getDestinationAccount() != null &&
                    tx.getDestinationAccount().getAccountNumber().equals(accountNumber);

            if ("SUCCESS".equalsIgnoreCase(tx.getStatus().name())) {
                if (isCredit) {
                    item.type = "CR";
                    item.credit = tx.getAmount();
                    item.debit = null;
                    running = running.add(tx.getAmount());
                } else {
                    item.type = "DR";
                    item.debit = tx.getAmount();
                    item.credit = null;
                    running = running.subtract(tx.getAmount());
                }
            } else {
                item.type = "FAILED";
                item.debit = null;
                item.credit = null;
            }

            item.runningBalance = running;
            report.items.add(item);
        }

        return report;
    }
}
