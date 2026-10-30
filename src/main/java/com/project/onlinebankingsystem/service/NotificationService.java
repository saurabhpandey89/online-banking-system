package com.project.onlinebankingsystem.service;

import com.project.onlinebankingsystem.model.Notification;
import com.project.onlinebankingsystem.model.User;
import com.project.onlinebankingsystem.repository.NotificationRepository;
import com.project.onlinebankingsystem.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;

@Service
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final UserRepository userRepository;

    public NotificationService(NotificationRepository notificationRepository, UserRepository userRepository) {
        this.notificationRepository = notificationRepository;
        this.userRepository = userRepository;
    }

    @Transactional
    public void createNotification(User user, String title, String message, String type) {
        if (user == null) return;
        Notification notif = Notification.builder()
                .user(user)
                .title(title)
                .message(message)
                .type(type)
                .isRead(false)
                .createdAt(LocalDateTime.now())
                .build();
        notificationRepository.save(notif);
    }

    @Transactional
    public void notifyTransaction(User user, String type, BigDecimal amount, String accNo, String ref) {
        if (user == null) return;
        String dateStr = LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd-MMM-yyyy HH:mm"));
        String title;
        String message;

        if ("DEPOSIT".equalsIgnoreCase(type) || "CREDIT".equalsIgnoreCase(type)) {
            title = "A/c Credited: ₹" + amount;
            message = "Dear Customer, INR " + amount + " has been CREDITED to your A/c " + maskAccount(accNo) +
                    " on " + dateStr + ". Ref: " + ref + " - Apex Horizon Bank";
        } else {
            title = "A/c Debited: ₹" + amount;
            message = "Dear Customer, INR " + amount + " has been DEBITED from your A/c " + maskAccount(accNo) +
                    " on " + dateStr + ". Ref: " + ref + ". If not done by you, freeze your card immediately - Apex Horizon Bank";
        }

        createNotification(user, title, message, "TRANSACTION");
    }

    @Transactional
    public void notifySecurity(User user, String title, String message) {
        createNotification(user, title, message, "SECURITY");
    }

    public List<Notification> getNotificationsForUser(String username) {
        User user = userRepository.findByUsername(username).orElse(null);
        if (user == null) return Collections.emptyList();
        return notificationRepository.findTop20ByUserOrderByCreatedAtDesc(user);
    }

    public long getUnreadCount(String username) {
        User user = userRepository.findByUsername(username).orElse(null);
        if (user == null) return 0;
        return notificationRepository.countByUserAndIsReadFalse(user);
    }

    @Transactional
    public void markAllAsRead(String username) {
        User user = userRepository.findByUsername(username).orElse(null);
        if (user == null) return;
        List<Notification> list = notificationRepository.findByUserOrderByCreatedAtDesc(user);
        for (Notification n : list) {
            n.setRead(true);
        }
        notificationRepository.saveAll(list);
    }

    private String maskAccount(String acc) {
        if (acc == null || acc.length() < 4) return acc != null ? acc : "XXXX";
        return "••" + acc.substring(acc.length() - 4);
    }
}
