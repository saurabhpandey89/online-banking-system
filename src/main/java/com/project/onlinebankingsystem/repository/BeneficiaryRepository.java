package com.project.onlinebankingsystem.repository;

import com.project.onlinebankingsystem.model.Beneficiary;
import com.project.onlinebankingsystem.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface BeneficiaryRepository extends JpaRepository<Beneficiary, Long> {
    List<Beneficiary> findByUser(User user);

    @Query("SELECT b FROM Beneficiary b WHERE LOWER(TRIM(b.user.username)) = LOWER(TRIM(:username))")
    List<Beneficiary> findByUserUsername(@Param("username") String username);
}
