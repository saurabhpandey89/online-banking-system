package com.project.onlinebankingsystem.repository;

import com.project.onlinebankingsystem.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    @Query("SELECT u FROM User u WHERE LOWER(TRIM(u.username)) = LOWER(TRIM(:username))")
    Optional<User> findByUsername(@Param("username") String username);

    @Query("SELECT u FROM User u WHERE LOWER(TRIM(u.username)) = LOWER(TRIM(:identifier)) OR LOWER(TRIM(u.email)) = LOWER(TRIM(:identifier)) OR TRIM(u.phone) = TRIM(:identifier) OR LOWER(TRIM(u.fullName)) = LOWER(TRIM(:identifier))")
    Optional<User> findByIdentifier(@Param("identifier") String identifier);

    Optional<User> findByEmail(String email);
    Optional<User> findByPhone(String phone);

    @Query("SELECT COUNT(u) > 0 FROM User u WHERE LOWER(TRIM(u.username)) = LOWER(TRIM(:username))")
    boolean existsByUsername(@Param("username") String username);

    boolean existsByEmail(String email);
    boolean existsByPhone(String phone);
    boolean existsByPan(String pan);
    boolean existsByAadhar(String aadhar);
}
