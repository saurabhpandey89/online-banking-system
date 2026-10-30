package com.project.onlinebankingsystem;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * ============================================================================
 *                APEX HORIZON BANK - ONLINE BANKING SYSTEM
 * ============================================================================
 * 
 * Welcome to Apex Horizon Bank! This is the main starting point of the application.
 * 
 * HOW IT WORKS (KAISE KAAM KARTA HAI):
 * 1. Spring Boot runs this main() method to boot up the entire backend server.
 * 2. It automatically sets up:
 *    - Database connection (H2 database: ./data/bankdb)
 *    - Spring Security (handles login, logout, roles: CUSTOMER and ADMIN)
 *    - MVC Controllers (renders HTML pages using Thymeleaf)
 *    - REST API Endpoints (handles mobile/API calls using JWT)
 * 3. Default port: http://localhost:8080
 * 
 * HOW TO RUN:
 * Run this file in your IDE (IntelliJ / Eclipse / VS Code) OR in terminal:
 *   .\mvnw.cmd spring-boot:run
 * ============================================================================
 */
@SpringBootApplication
public class OnlinebankingsystemApplication {

	public static void main(String[] args) {
		SpringApplication.run(OnlinebankingsystemApplication.class, args);
	}

}
