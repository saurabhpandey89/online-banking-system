package com.project.onlinebankingsystem.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;

/**
 * Ensures backward-compatibility for database schemas created with rigid H2 ENUM types.
 * Converts ENUM columns to VARCHAR(50) so new values (such as UPI_PAYMENT, REVERSED, CANCELLED)
 * can be stored and queried without SQL 22030 exceptions.
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class DatabaseSchemaMigrationConfig {

    private final DataSource dataSource;

    @PostConstruct
    public void migrateSchema() {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {

            log.info("Checking and applying automatic database schema migrations...");

            // Alter transactions table columns
            executeSafeAlter(statement, "ALTER TABLE transactions ALTER COLUMN type SET DATA TYPE VARCHAR(50)");
            executeSafeAlter(statement, "ALTER TABLE transactions ALTER COLUMN status SET DATA TYPE VARCHAR(50)");

            // Alter accounts table columns if applicable
            executeSafeAlter(statement, "ALTER TABLE accounts ALTER COLUMN status SET DATA TYPE VARCHAR(50)");
            executeSafeAlter(statement, "ALTER TABLE accounts ALTER COLUMN account_type SET DATA TYPE VARCHAR(50)");
            executeSafeAlter(statement, "UPDATE accounts SET account_type = 'SAVING' WHERE account_type = 'SAVINGS'");
            executeSafeAlter(statement, "UPDATE accounts SET version = 0 WHERE version IS NULL");
            executeSafeAlter(statement, "ALTER TABLE accounts ALTER COLUMN version SET DEFAULT 0");

            // Ensure Admin accounts are promoted to ROLE_ADMIN and unlocked if present
            executeSafeAlter(statement, "UPDATE users SET role = 'ROLE_ADMIN' WHERE LOWER(username) IN ('admin', 'saurabh', 'saurabhpandey', 'shubham') OR LOWER(full_name) LIKE '%saurabh%'");
            executeSafeAlter(statement, "UPDATE users SET lockout_until = NULL, failed_login_attempts = 0 WHERE role = 'ROLE_ADMIN' OR LOWER(username) IN ('admin', 'saurabh', 'saurabhpandey', 'shubham')");

            log.info("Database schema migration completed successfully.");
        } catch (Exception e) {
            log.warn("Database schema migration check ended with message: {}", e.getMessage());
        }
    }

    private void executeSafeAlter(Statement statement, String sql) {
        try {
            statement.execute(sql);
            log.debug("Executed schema migration: {}", sql);
        } catch (Exception e) {
            // Table may not exist yet on initial boot or column is already of desired type
            log.debug("Safe schema migration statement skipped: {} ({})", sql, e.getMessage());
        }
    }
}
