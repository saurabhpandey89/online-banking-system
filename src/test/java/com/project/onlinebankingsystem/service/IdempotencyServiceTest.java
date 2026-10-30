package com.project.onlinebankingsystem.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.project.onlinebankingsystem.dto.TransactionResponse;
import com.project.onlinebankingsystem.enums.IdempotencyStatus;
import com.project.onlinebankingsystem.model.IdempotencyRecord;
import com.project.onlinebankingsystem.repository.IdempotencyRecordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class IdempotencyServiceTest {

    @Mock
    private IdempotencyRecordRepository repository;

    private ObjectMapper objectMapper;
    private IdempotencyService idempotencyService;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        idempotencyService = new IdempotencyService(repository, objectMapper);
    }

    @Test
    @DisplayName("computeRequestHash: Should generate identical SHA-256 hash for identical parameters")
    void testComputeRequestHash_Deterministic() {
        String hash1 = idempotencyService.computeRequestHash("TRANSFER", "ACC-1001", "ACC-2002", new BigDecimal("500.00"));
        String hash2 = idempotencyService.computeRequestHash("TRANSFER", "ACC-1001", "ACC-2002", new BigDecimal("500.000"));
        assertNotNull(hash1);
        assertEquals(hash1, hash2, "Normalizing amounts (500.00 vs 500.000) should produce identical hash");

        String hashDiff = idempotencyService.computeRequestHash("TRANSFER", "ACC-1001", "ACC-2002", new BigDecimal("600.00"));
        assertNotEquals(hash1, hashDiff, "Different amounts must produce different hashes");
    }

    @Test
    @DisplayName("checkIdempotency: When key is null or blank, returns null immediately")
    void testCheckIdempotency_NullOrBlankKey() {
        assertNull(idempotencyService.checkIdempotency(null, "TRANSFER", "hash"));
        assertNull(idempotencyService.checkIdempotency("   ", "TRANSFER", "hash"));
        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("checkIdempotency: When key not found, returns null (allowing new execution)")
    void testCheckIdempotency_KeyNotFound() {
        when(repository.findByIdempotencyKey("KEY-123")).thenReturn(Optional.empty());

        TransactionResponse result = idempotencyService.checkIdempotency("KEY-123", "TRANSFER", "hash123");
        assertNull(result);
        verify(repository).findByIdempotencyKey("KEY-123");
    }

    @Test
    @DisplayName("checkIdempotency: When record status is PROCESSING, throws IllegalStateException")
    void testCheckIdempotency_StatusProcessing() {
        IdempotencyRecord record = IdempotencyRecord.builder()
                .idempotencyKey("KEY-123")
                .requestHash("hash123")
                .status(IdempotencyStatus.PROCESSING)
                .createdAt(LocalDateTime.now())
                .expiresAt(LocalDateTime.now().plusHours(24))
                .build();

        when(repository.findByIdempotencyKey("KEY-123")).thenReturn(Optional.of(record));

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                idempotencyService.checkIdempotency("KEY-123", "TRANSFER", "hash123"));

        assertTrue(ex.getMessage().contains("currently in progress"));
    }

    @Test
    @DisplayName("checkIdempotency: When payload hash differs, throws IllegalArgumentException (Tampering/Reuse)")
    void testCheckIdempotency_HashMismatch() {
        IdempotencyRecord record = IdempotencyRecord.builder()
                .idempotencyKey("KEY-123")
                .requestHash("hash-original")
                .status(IdempotencyStatus.COMPLETED)
                .createdAt(LocalDateTime.now())
                .expiresAt(LocalDateTime.now().plusHours(24))
                .build();

        when(repository.findByIdempotencyKey("KEY-123")).thenReturn(Optional.of(record));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                idempotencyService.checkIdempotency("KEY-123", "TRANSFER", "hash-tampered"));

        assertTrue(ex.getMessage().contains("Idempotency Conflict"));
    }

    @Test
    @DisplayName("checkIdempotency: When record is COMPLETED, deserializes cached response and flags idempotentReplay")
    void testCheckIdempotency_Completed_Replay() throws Exception {
        TransactionResponse originalResp = TransactionResponse.builder()
                .transactionReference("TXN-SUCCESS-999")
                .sourceAccountNumber("ACC-1001")
                .destinationAccountNumber("ACC-2002")
                .amount(new BigDecimal("1500.00"))
                .status("SUCCESS")
                .build();

        IdempotencyRecord record = IdempotencyRecord.builder()
                .idempotencyKey("KEY-123")
                .requestHash("hash123")
                .status(IdempotencyStatus.COMPLETED)
                .transactionReference("TXN-SUCCESS-999")
                .responsePayload(objectMapper.writeValueAsString(originalResp))
                .createdAt(LocalDateTime.now())
                .expiresAt(LocalDateTime.now().plusHours(24))
                .build();

        when(repository.findByIdempotencyKey("KEY-123")).thenReturn(Optional.of(record));

        TransactionResponse result = idempotencyService.checkIdempotency("KEY-123", "TRANSFER", "hash123");
        assertNotNull(result);
        assertEquals("TXN-SUCCESS-999", result.getTransactionReference());
        assertEquals("KEY-123", result.getIdempotencyKey());
        assertTrue(result.isIdempotentReplay());
    }

    @Test
    @DisplayName("createProcessingRecord: Persists initial PROCESSING record")
    void testCreateProcessingRecord() {
        idempotencyService.createProcessingRecord("KEY-NEW", "TRANSFER", "hash-new");

        ArgumentCaptor<IdempotencyRecord> captor = ArgumentCaptor.forClass(IdempotencyRecord.class);
        verify(repository).save(captor.capture());

        IdempotencyRecord saved = captor.getValue();
        assertEquals("KEY-NEW", saved.getIdempotencyKey());
        assertEquals(IdempotencyStatus.PROCESSING, saved.getStatus());
        assertEquals("hash-new", saved.getRequestHash());
    }

    @Test
    @DisplayName("markCompleted: Updates record with COMPLETED status and serialized payload")
    void testMarkCompleted() {
        IdempotencyRecord record = IdempotencyRecord.builder()
                .idempotencyKey("KEY-DONE")
                .status(IdempotencyStatus.PROCESSING)
                .build();

        when(repository.findByIdempotencyKey("KEY-DONE")).thenReturn(Optional.of(record));

        TransactionResponse resp = TransactionResponse.builder()
                .transactionReference("TXN-REF-777")
                .build();

        idempotencyService.markCompleted("KEY-DONE", resp);

        verify(repository).save(record);
        assertEquals(IdempotencyStatus.COMPLETED, record.getStatus());
        assertEquals("TXN-REF-777", record.getTransactionReference());
        assertNotNull(record.getResponsePayload());
    }

    @Test
    @DisplayName("markFailed: Updates record status to FAILED")
    void testMarkFailed() {
        IdempotencyRecord record = IdempotencyRecord.builder()
                .idempotencyKey("KEY-FAIL")
                .status(IdempotencyStatus.PROCESSING)
                .build();

        when(repository.findByIdempotencyKey("KEY-FAIL")).thenReturn(Optional.of(record));

        idempotencyService.markFailed("KEY-FAIL");

        verify(repository).save(record);
        assertEquals(IdempotencyStatus.FAILED, record.getStatus());
    }
}
