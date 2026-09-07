package com.jrpay.ledger.admin;

import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.startupcheck.IsRunningStartupCheckStrategy;

/**
 * Layer 2/3 integration test base for the A1 admin surface.
 *
 * <p>Resolution order matches the identity-service convention:
 * <ol>
 *   <li>If {@code LEDGER_TEST_DB_URL} is set, use that JDBC URL plus
 *       {@code LEDGER_TEST_DB_USER} / {@code LEDGER_TEST_DB_PASSWORD}
 *       (default {@code jrpay}/{@code jrpay}).
 *   <li>Otherwise spin up a Testcontainers Postgres.
 * </ol>
 *
 * <p>The {@code admin-it} profile is active so the application configures
 * its OAuth2 resource server (rather than the demo-only HTTP Basic chain).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("admin-it")
@ExtendWith(LedgerRequireRealPostgres.class)
public abstract class LedgerAdminIntegrationTestBase {

    private static final String DB_URL_ENV = "LEDGER_TEST_DB_URL";
    private static final String DB_USER_ENV = "LEDGER_TEST_DB_USER";
    private static final String DB_PASSWORD_ENV = "LEDGER_TEST_DB_PASSWORD";

    static String jdbcUrl;
    static String jdbcUser;
    static String jdbcPassword;
    private static PostgreSQLContainer<?> postgres;

    static synchronized boolean ensureDatabaseReady() {
        if (jdbcUrl != null) return true;
        String envUrl = System.getenv(DB_URL_ENV);
        if (envUrl != null && !envUrl.isBlank()) {
            jdbcUrl = envUrl;
            jdbcUser = orDefault(System.getenv(DB_USER_ENV), "jrpay");
            jdbcPassword = orDefault(System.getenv(DB_PASSWORD_ENV), "jrpay");
            return true;
        }
        try {
            PostgreSQLContainer<?> c = new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("ledger_test")
                    .withUsername("ledger_test")
                    .withPassword("ledger_test")
                    .withStartupCheckStrategy(new IsRunningStartupCheckStrategy());
            c.start();
            postgres = c;
            jdbcUrl = c.getJdbcUrl();
            jdbcUser = c.getUsername();
            jdbcPassword = c.getPassword();
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        ensureDatabaseReady();
        registry.add("spring.datasource.url", () -> jdbcUrl);
        registry.add("spring.datasource.username", () -> jdbcUser);
        registry.add("spring.datasource.password", () -> jdbcPassword);
        registry.add("spring.flyway.url", () -> jdbcUrl);
        registry.add("spring.flyway.user", () -> jdbcUser);
        registry.add("spring.flyway.password", () -> jdbcPassword);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        // Kafka publishes happen against the dev compose Kafka on port 9092
        // (the audit-query module reads them in its own Layer-2 tests). The
        // tests verify the publisher was called with the expected payload via
        // a captured KafkaTemplate; record durability is exercised end-to-end
        // by the smoke-tests module.
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
