package com.project.onlinebankingsystem.service;

import com.project.onlinebankingsystem.model.AuditLog;
import com.project.onlinebankingsystem.repository.AuditLogRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class AuditService {

    private final AuditLogRepository auditLogRepository;

    public AuditService(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    @Transactional
    public void log(String username, String action, String details, String ipAddress, String status) {
        try {
            AuditLog auditLog = AuditLog.builder()
                    .username(username != null ? username : "SYSTEM")
                    .action(action)
                    .details(details)
                    .ipAddress(ipAddress != null ? ipAddress : "127.0.0.1")
                    .status(status != null ? status : "SUCCESS")
                    .timestamp(LocalDateTime.now())
                    .build();
            auditLogRepository.save(auditLog);
        } catch (Exception e) {
            // Fail safe: audit log should not disrupt primary business flow
            System.err.println("Audit log error: " + e.getMessage());
        }
    }

    public void log(String username, String action, String details, HttpServletRequest request, String status) {
        String ip = "127.0.0.1";
        if (request != null) {
            ip = request.getHeader("X-Forwarded-For");
            if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
                ip = request.getRemoteAddr();
            }
        }
        log(username, action, details, ip, status);
    }

    public List<AuditLog> getAllAuditLogs() {
        return auditLogRepository.findTop50ByOrderByTimestampDesc();
    }

    public List<AuditLog> getUserAuditLogs(String username) {
        return auditLogRepository.findByUsernameOrderByTimestampDesc(username);
    }
}
