package com.project.onlinebankingsystem.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.onlinebankingsystem.dto.TransactionResponse;
import com.project.onlinebankingsystem.enums.IdempotencyStatus;
import com.project.onlinebankingsystem.model.IdempotencyRecord;
import com.project.onlinebankingsystem.repository.IdempotencyRecordRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.Optional;

@Service
public class IdempotencyService {

    private final IdempotencyRecordRepository idempotencyRecordRepository;
    private final ObjectMapper objectMapper;

    public IdempotencyService(IdempotencyRecordRepository idempotencyRecordRepository, ObjectMapper objectMapper) {
        this.idempotencyRecordRepository = idempotencyRecordRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Computes a SHA-256 fingerprint hash of the critical transaction parameters
     * to protect against idempotency key collisions / payload tampering.
     */
    public String computeRequestHash(String endpoint, String sourceAcc, String destAcc, BigDecimal amount) {
        try {
            String raw = String.format("%s|%s|%s|%s",
                    endpoint != null ? endpoint.trim() : "",
                    sourceAcc != null ? sourceAcc.trim() : "",
                    destAcc != null ? destAcc.trim() : "",
                    amount != null ? amount.stripTrailingZeros().toPlainString() : "");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            return String.valueOf((endpoint + sourceAcc + destAcc + amount).hashCode());
        }
    }

    /**
     * Checks if an idempotency key has already been seen:
     * - If COMPLETED and matching hash: returns the cached TransactionResponse (Idempotent Replay).
     * - If PROCESSING: throws IllegalStateException to prevent concurrent double-execution.
     * - If payload hash mismatch: throws IllegalArgumentException to flag idempotency key reuse.
     * - If not seen or expired: returns null (allowing new execution).
     */
    public TransactionResponse checkIdempotency(String idempotencyKey, String endpoint, String requestHash) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return null;
        }

        Optional<IdempotencyRecord> recordOpt = idempotencyRecordRepository.findByIdempotencyKey(idempotencyKey.trim());
        if (recordOpt.isEmpty()) {
            return null;
        }

        IdempotencyRecord record = recordOpt.get();

        if (record.isExpired()) {
            idempotencyRecordRepository.delete(record);
            return null;
        }

        if (!record.getRequestHash().equals(requestHash)) {
            throw new IllegalArgumentException("Idempotency Conflict: Key '" + idempotencyKey + 
                    "' was already used with a different transaction payload.");
        }

        if (record.getStatus() == IdempotencyStatus.PROCESSING) {
            throw new IllegalStateException("Transaction with request key '" + idempotencyKey + 
                    "' is currently in progress. Please wait a moment and do not retry immediately.");
        }

        if (record.getStatus() == IdempotencyStatus.COMPLETED && record.getResponsePayload() != null) {
            try {
                TransactionResponse cachedResponse = objectMapper.readValue(record.getResponsePayload(), TransactionResponse.class);
                cachedResponse.setIdempotentReplay(true);
                cachedResponse.setIdempotencyKey(idempotencyKey);
                return cachedResponse;
            } catch (Exception e) {
                System.err.println("Failed to deserialize cached idempotency payload: " + e.getMessage());
            }
        }

        return null;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void createProcessingRecord(String idempotencyKey, String endpoint, String requestHash) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return;
        }

        IdempotencyRecord record = IdempotencyRecord.builder()
                .idempotencyKey(idempotencyKey.trim())
                .endpoint(endpoint != null ? endpoint : "TRANSACTION")
                .requestHash(requestHash)
                .status(IdempotencyStatus.PROCESSING)
                .createdAt(LocalDateTime.now())
                .expiresAt(LocalDateTime.now().plusHours(24))
                .build();

        idempotencyRecordRepository.save(record);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markCompleted(String idempotencyKey, TransactionResponse response) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return;
        }

        Optional<IdempotencyRecord> recordOpt = idempotencyRecordRepository.findByIdempotencyKey(idempotencyKey.trim());
        if (recordOpt.isPresent()) {
            IdempotencyRecord record = recordOpt.get();
            record.setStatus(IdempotencyStatus.COMPLETED);
            record.setTransactionReference(response != null ? response.getTransactionReference() : null);
            try {
                record.setResponsePayload(objectMapper.writeValueAsString(response));
            } catch (Exception e) {
                System.err.println("Failed to serialize idempotency response payload: " + e.getMessage());
            }
            idempotencyRecordRepository.save(record);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return;
        }

        Optional<IdempotencyRecord> recordOpt = idempotencyRecordRepository.findByIdempotencyKey(idempotencyKey.trim());
        if (recordOpt.isPresent()) {
            IdempotencyRecord record = recordOpt.get();
            record.setStatus(IdempotencyStatus.FAILED);
            idempotencyRecordRepository.save(record);
        }
    }
}
