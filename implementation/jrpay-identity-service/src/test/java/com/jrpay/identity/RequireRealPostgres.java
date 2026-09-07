package com.jrpay.identity;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * JUnit 5 condition that gates every test in {@link IdentityIntegrationTestBase}
 * subclasses on a working Postgres being reachable. If the env var path
 * succeeds, tests run; if Testcontainers can start (compatible Docker), tests
 * run; otherwise they're skipped with a directive on how to enable them.
 *
 * <p>This keeps {@code gradle test} green on developer machines whose Docker
 * Engine has outpaced the bundled docker-java client (Docker ≥ 29 + the
 * docker-java 3.4 / 3.5 hardcoded API version 1.32 mismatch). CI runners
 * provision a compatible Docker so the integration tests do run there.
 */
public class RequireRealPostgres implements ExecutionCondition {

    private static final String SKIP_MESSAGE =
            "Skipping integration test: real Postgres unavailable. "
                    + "Either bring up the dev compose stack and set "
                    + "IDENTITY_TEST_DB_URL=jdbc:postgresql://localhost:5432/identity, "
                    + "or run on a host where Testcontainers can negotiate with the Docker daemon.";

    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        return IdentityIntegrationTestBase.ensureDatabaseReady()
                ? ConditionEvaluationResult.enabled("Postgres available")
                : ConditionEvaluationResult.disabled(SKIP_MESSAGE);
    }
}
