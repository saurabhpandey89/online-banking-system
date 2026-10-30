package com.project.onlinebankingsystem.repository;

import com.project.onlinebankingsystem.model.Notification;
import com.project.onlinebankingsystem.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface NotificationRepository extends JpaRepository<Notification, Long> {
    List<Notification> findByUserOrderByCreatedAtDesc(User user);
    List<Notification> findTop20ByUserOrderByCreatedAtDesc(User user);
    long countByUserAndIsReadFalse(User user);
}
