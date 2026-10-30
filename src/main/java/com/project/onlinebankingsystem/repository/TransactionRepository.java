package com.project.onlinebankingsystem.repository;

import com.project.onlinebankingsystem.enums.TransactionStatus;
import com.project.onlinebankingsystem.model.Account;
import com.project.onlinebankingsystem.model.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface TransactionRepository extends JpaRepository<Transaction, Long> {
    @Query("SELECT t FROM Transaction t WHERE t.sourceAccount = :account OR t.destinationAccount = :account ORDER BY t.timestamp DESC")
    List<Transaction> findAllByAccount(@Param("account") Account account);

    List<Transaction> findAllByOrderByTimestampDesc();

    Optional<Transaction> findByTransactionReference(String transactionReference);

    Optional<Transaction> findByIdempotencyKey(String idempotencyKey);

    Optional<Transaction> findByNpciRrn(String npciRrn);

    @Query("SELECT COALESCE(SUM(t.amount), 0) FROM Transaction t WHERE t.sourceAccount = :account AND t.status = com.project.onlinebankingsystem.enums.TransactionStatus.SUCCESS AND t.type IN (com.project.onlinebankingsystem.enums.TransactionType.TRANSFER, com.project.onlinebankingsystem.enums.TransactionType.WITHDRAWAL, com.project.onlinebankingsystem.enums.TransactionType.UPI_PAYMENT) AND t.timestamp >= :startOfDay AND t.timestamp < :endOfDay")
    BigDecimal sumDailyDebits(@Param("account") Account account, @Param("startOfDay") LocalDateTime startOfDay, @Param("endOfDay") LocalDateTime endOfDay);

    @Query("SELECT t FROM Transaction t WHERE (t.sourceAccount = :account OR t.destinationAccount = :account) AND t.timestamp >= :startDate AND t.timestamp <= :endDate ORDER BY t.timestamp ASC")
    List<Transaction> findByAccountAndDateRange(@Param("account") Account account, @Param("startDate") LocalDateTime startDate, @Param("endDate") LocalDateTime endDate);

    List<Transaction> findByStatusOrderByTimestampDesc(TransactionStatus status);

    @Query("SELECT t FROM Transaction t WHERE (t.sourceAccount = :account OR t.destinationAccount = :account) AND t.status = :status ORDER BY t.timestamp DESC")
    List<Transaction> findByAccountAndStatus(@Param("account") Account account, @Param("status") TransactionStatus status);
}
