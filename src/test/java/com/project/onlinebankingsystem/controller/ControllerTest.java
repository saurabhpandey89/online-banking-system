package com.project.onlinebankingsystem.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.onlinebankingsystem.dto.*;
import com.project.onlinebankingsystem.enums.TransactionStatus;
import com.project.onlinebankingsystem.enums.TransactionType;
import com.project.onlinebankingsystem.enums.UpiPayeeType;
import com.project.onlinebankingsystem.service.TransactionService;
import com.project.onlinebankingsystem.service.UpiService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class ControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private TransactionService transactionService;

    @MockBean
    private UpiService upiService;

    @Test
    @WithMockUser(username = "customer1", authorities = {"ROLE_CUSTOMER"})
    @DisplayName("Deposit API: Valid deposit request returns 200 OK with TransactionResponse")
    void testDeposit_Success() throws Exception {
        DepositWithdrawRequest request = new DepositWithdrawRequest("ACC1001", new BigDecimal("5000.00"), "Deposit test", null);

        TransactionResponse mockResponse = TransactionResponse.builder()
                .transactionReference("TXN-DEP-001")
                .sourceAccountNumber("ACC1001")
                .amount(new BigDecimal("5000.00"))
                .type(TransactionType.DEPOSIT.name())
                .status(TransactionStatus.SUCCESS.name())
                .timestamp(LocalDateTime.now())
                .idempotentReplay(false)
                .build();

        when(transactionService.deposit(eq("ACC1001"), eq(new BigDecimal("5000.00")), anyString(), any()))
                .thenReturn(mockResponse);

        mockMvc.perform(post("/api/transactions/deposit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactionReference").value("TXN-DEP-001"))
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.amount").value(5000.00));
    }

    @Test
    @WithMockUser(username = "customer1", authorities = {"ROLE_CUSTOMER"})
    @DisplayName("Withdraw API: Valid withdraw request returns 200 OK")
    void testWithdraw_Success() throws Exception {
        DepositWithdrawRequest request = new DepositWithdrawRequest("ACC1001", new BigDecimal("1000.00"), "Cash withdrawal", "1234");

        TransactionResponse mockResponse = TransactionResponse.builder()
                .transactionReference("TXN-WTH-001")
                .sourceAccountNumber("ACC1001")
                .amount(new BigDecimal("1000.00"))
                .type(TransactionType.WITHDRAWAL.name())
                .status(TransactionStatus.SUCCESS.name())
                .timestamp(LocalDateTime.now())
                .build();

        when(transactionService.withdraw(eq("ACC1001"), eq(new BigDecimal("1000.00")), anyString(), eq("1234"), any()))
                .thenReturn(mockResponse);

        mockMvc.perform(post("/api/transactions/withdraw")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactionReference").value("TXN-WTH-001"))
                .andExpect(jsonPath("$.status").value("SUCCESS"));
    }

    @Test
    @WithMockUser(username = "customer1", authorities = {"ROLE_CUSTOMER"})
    @DisplayName("Transfer API: Valid transfer with Idempotency Key header returns 200 OK and replay header")
    void testTransfer_Success() throws Exception {
        TransferRequest request = new TransferRequest("SRC101", "DST202", new BigDecimal("1000.00"), "Transfer test", "1234");

        TransactionResponse mockResponse = TransactionResponse.builder()
                .transactionReference("TXN-TRF-001")
                .sourceAccountNumber("SRC101")
                .destinationAccountNumber("DST202")
                .amount(new BigDecimal("1000.00"))
                .type(TransactionType.TRANSFER.name())
                .status(TransactionStatus.SUCCESS.name())
                .timestamp(LocalDateTime.now())
                .idempotentReplay(false)
                .build();

        when(transactionService.transfer(eq("SRC101"), eq("DST202"), eq(new BigDecimal("1000.00")), anyString(), eq("1234"), eq("KEY-12345")))
                .thenReturn(mockResponse);

        mockMvc.perform(post("/api/transactions/transfer")
                        .header("X-Idempotency-Key", "KEY-12345")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Idempotent-Replay", "false"))
                .andExpect(jsonPath("$.transactionReference").value("TXN-TRF-001"))
                .andExpect(jsonPath("$.status").value("SUCCESS"));
    }

    @Test
    @WithMockUser(username = "customer1", authorities = {"ROLE_CUSTOMER"})
    @DisplayName("Transfer API: Validation fails with 400 Bad Request if mandatory fields are missing")
    void testTransfer_ValidationFailure_MissingFields() throws Exception {
        TransferRequest invalidRequest = new TransferRequest(); // Missing source, dest, and amount

        mockMvc.perform(post("/api/transactions/transfer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidRequest)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(username = "customer1", authorities = {"ROLE_CUSTOMER"})
    @DisplayName("Transfer API: Validation fails with 400 Bad Request if amount is below minimum ₹1.00")
    void testTransfer_ValidationFailure_BelowMinimum() throws Exception {
        TransferRequest belowMinRequest = new TransferRequest("SRC101", "DST202", new BigDecimal("0.50"), "Under min", "1234");

        mockMvc.perform(post("/api/transactions/transfer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(belowMinRequest)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(username = "customer1", authorities = {"ROLE_CUSTOMER"})
    @DisplayName("History API: Returns transaction history list with 200 OK")
    void testHistory_Success() throws Exception {
        TransactionResponse tx = TransactionResponse.builder()
                .transactionReference("TXN-HIST-01")
                .sourceAccountNumber("ACC1001")
                .amount(new BigDecimal("1500.00"))
                .type(TransactionType.TRANSFER.name())
                .status(TransactionStatus.SUCCESS.name())
                .timestamp(LocalDateTime.now())
                .build();

        when(transactionService.getTransactionHistory("ACC1001")).thenReturn(List.of(tx));

        mockMvc.perform(get("/api/transactions/history/ACC1001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].transactionReference").value("TXN-HIST-01"))
                .andExpect(jsonPath("$[0].amount").value(1500.00));
    }

    @Test
    @WithMockUser(username = "customer1", authorities = {"ROLE_CUSTOMER"})
    @DisplayName("UPI Validate API: Resolves payee and returns 200 OK")
    void testUpiValidate_Success() throws Exception {
        UpiPayeeValidationResponse mockValidation = UpiPayeeValidationResponse.builder()
                .valid(true)
                .vpa("merchant@icici")
                .verifiedName("Merchant Store")
                .payeeType(UpiPayeeType.MERCHANT)
                .merchantCode("5411")
                .maxAllowedAmount(new BigDecimal("200000.00"))
                .suggestedRrn("123456789012")
                .build();

        when(upiService.validatePayee("merchant@icici")).thenReturn(mockValidation);

        mockMvc.perform(post("/api/upi/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"qrData\":\"merchant@icici\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.verifiedName").value("Merchant Store"))
                .andExpect(jsonPath("$.suggestedRrn").value("123456789012"));
    }

    @Test
    @WithMockUser(username = "customer1", authorities = {"ROLE_CUSTOMER"})
    @DisplayName("UPI Pay API: Valid payment request executes and returns 200 OK receipt")
    void testUpiPay_Success() throws Exception {
        UpiPaymentRequest request = UpiPaymentRequest.builder()
                .sourceAccountNumber("ACC1001")
                .payeeVpa("swiggy@icici")
                .amount(new BigDecimal("350.00"))
                .pin("1234")
                .description("Food Delivery")
                .npciRrn("628100123456")
                .idempotencyKey("IDEMP-UPI-123")
                .build();

        UpiPaymentResponse mockReceipt = UpiPaymentResponse.builder()
                .status("SUCCESS")
                .responseCode("00")
                .npciRrn("628100123456")
                .transactionReference("TXN-UPI-999")
                .sourceAccountNumber("ACC1001")
                .payeeVpa("swiggy@icici")
                .payeeName("Swiggy")
                .amount(new BigDecimal("350.00"))
                .timestamp(LocalDateTime.now())
                .build();

        when(upiService.executeUpiPayment(any(UpiPaymentRequest.class), eq("customer1"))).thenReturn(mockReceipt);

        mockMvc.perform(post("/api/upi/pay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.responseCode").value("00"))
                .andExpect(jsonPath("$.npciRrn").value("628100123456"))
                .andExpect(jsonPath("$.amount").value(350.00));
    }
}
