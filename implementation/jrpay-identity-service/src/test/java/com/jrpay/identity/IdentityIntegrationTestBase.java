package com.jrpay.identity;

import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.startupcheck.IsRunningStartupCheckStrategy;

/**
 * Base for Layer 2 / Layer 3 tests that require a real Postgres.
 *
 * <p>Resolution order:
 * <ol>
 *   <li>If env var {@code IDENTITY_TEST_DB_URL} is set, use that JDBC URL
 *       (with {@code IDENTITY_TEST_DB_USER} / {@code IDENTITY_TEST_DB_PASSWORD}
 *       — both default to {@code jrpay}). This is the path the docker-compose
 *       dev stack takes — point at the running Postgres on port 5432.
 *   <li>Otherwise, spin up a Testcontainers Postgres. CI runners usually have
 *       a recent-enough Docker engine for this to work; on hosts where the
 *       Docker API version is too new for the bundled docker-java client, a
 *       conditional skip kicks in (see {@link RequireRealPostgres}).
 * </ol>
 *
 * <p>Each test class gets a unique schema (via Flyway's default schema in the
 * shared database) but tests share the JDBC connection pool / Spring context.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@ExtendWith(RequireRealPostgres.class)
public abstract class IdentityIntegrationTestBase {

    private static final String DB_URL_ENV = "IDENTITY_TEST_DB_URL";
    private static final String DB_USER_ENV = "IDENTITY_TEST_DB_USER";
    private static final String DB_PASSWORD_ENV = "IDENTITY_TEST_DB_PASSWORD";

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
                    .withDatabaseName("identity_test")
                    .withUsername("identity_test")
                    .withPassword("identity_test")
                    .withStartupCheckStrategy(new IsRunningStartupCheckStrategy());
            c.start();
            postgres = c;
            jdbcUrl = c.getJdbcUrl();
            jdbcUser = c.getUsername();
            jdbcPassword = c.getPassword();
            return true;
        } catch (Throwable e) {
            // Likely Docker API mismatch on this host; tests will be skipped
            // by the @ExtendWith(RequireRealPostgres.class).
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
