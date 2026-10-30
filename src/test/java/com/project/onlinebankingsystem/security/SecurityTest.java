package com.project.onlinebankingsystem.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class SecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private com.project.onlinebankingsystem.repository.UserRepository userRepository;

    @org.junit.jupiter.api.BeforeEach
    void setUpUser() {
        if (!userRepository.existsByUsername("regular_user")) {
            userRepository.save(com.project.onlinebankingsystem.model.User.builder()
                    .username("regular_user")
                    .password("secret")
                    .fullName("Regular Customer")
                    .email("regular@example.com")
                    .phone("9988776655")
                    .role(com.project.onlinebankingsystem.enums.Role.ROLE_CUSTOMER)
                    .build());
        }
    }

    @Test
    @DisplayName("Public Access: Landing page '/' is accessible without authentication")
    void testPublicEndpoint_Home() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Public Access: Login page '/login' is accessible without authentication")
    void testPublicEndpoint_Login() throws Exception {
        mockMvc.perform(get("/login"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Public Access: Register page '/register' is accessible without authentication")
    void testPublicEndpoint_Register() throws Exception {
        mockMvc.perform(get("/register"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Unauthenticated Access: Protected dashboard redirects to '/login'")
    void testUnauthenticated_DashboardRedirectsToLogin() throws Exception {
        mockMvc.perform(get("/dashboard"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));
    }

    @Test
    @DisplayName("Unauthenticated Access: Admin API redirects or denies without authentication")
    void testUnauthenticated_AdminApiDenied() throws Exception {
        mockMvc.perform(get("/api/admin/users"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));
    }

    @Test
    @WithMockUser(username = "customer1", authorities = {"ROLE_CUSTOMER"})
    @DisplayName("Role Authorization: Customer accessing '/api/admin/users' is blocked with 403 Forbidden")
    void testCustomer_AdminAccessDenied() throws Exception {
        mockMvc.perform(get("/api/admin/users"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "admin_super", authorities = {"ROLE_ADMIN"})
    @DisplayName("Role Authorization: Admin accessing '/api/admin/users' is allowed with 200 OK")
    void testAdmin_AdminAccessGranted() throws Exception {
        mockMvc.perform(get("/api/admin/users"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "regular_user", authorities = {"ROLE_CUSTOMER"})
    @DisplayName("Authenticated Access: Customer accessing dashboard is authenticated")
    void testAuthenticated_CustomerDashboardAccess() throws Exception {
        mockMvc.perform(get("/dashboard"))
                .andExpect(status().isOk());
    }
}
