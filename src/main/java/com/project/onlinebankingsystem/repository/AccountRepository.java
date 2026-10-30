package com.project.onlinebankingsystem.repository;

import com.project.onlinebankingsystem.model.Account;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AccountRepository extends JpaRepository<Account, Long> {
    Optional<Account> findByAccountNumber(String accountNumber);
    Optional<Account> findByUpiId(String upiId);
    boolean existsByAccountNumber(String accountNumber);

    @Query("SELECT a FROM Account a WHERE LOWER(TRIM(a.user.username)) = LOWER(TRIM(:username))")
    List<Account> findByUserUsername(@Param("username") String username);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM Account a WHERE a.accountNumber = :accountNumber")
    Optional<Account> findByAccountNumberWithLock(@Param("accountNumber") String accountNumber);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM Account a WHERE LOWER(TRIM(a.upiId)) = LOWER(TRIM(:upiId))")
    Optional<Account> findByUpiIdWithLock(@Param("upiId") String upiId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM Account a WHERE a.id = :id")
    Optional<Account> findByIdWithLock(@Param("id") Long id);
}
