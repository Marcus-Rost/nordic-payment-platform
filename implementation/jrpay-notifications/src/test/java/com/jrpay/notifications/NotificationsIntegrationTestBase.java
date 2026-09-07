package com.jrpay.notifications;

import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.startupcheck.IsRunningStartupCheckStrategy;

/**
 * Mirrors the pattern from {@code jrpay-identity-service} — Testcontainers
 * Postgres by default, env-var override for the dev compose stack when the
 * local Docker daemon's API version trips Testcontainers.
 *
 * <p>Env vars (all optional, all default to the dev compose stack
 * convention):
 * <ul>
 *   <li>{@code NOTIFICATIONS_TEST_DB_URL=jdbc:postgresql://localhost:5432/notifications}
 *   <li>{@code NOTIFICATIONS_TEST_DB_USER=jrpay}
 *   <li>{@code NOTIFICATIONS_TEST_DB_PASSWORD=jrpay}
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@ExtendWith(RequireRealPostgres.class)
public abstract class NotificationsIntegrationTestBase {

    private static final String DB_URL_ENV = "NOTIFICATIONS_TEST_DB_URL";
    private static final String DB_USER_ENV = "NOTIFICATIONS_TEST_DB_USER";
    private static final String DB_PASSWORD_ENV = "NOTIFICATIONS_TEST_DB_PASSWORD";

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
                    .withDatabaseName("notifications_test")
                    .withUsername("notifications_test")
                    .withPassword("notifications_test")
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
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
