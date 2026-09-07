package com.jrpay.notifications;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Skips integration tests when no Postgres is reachable. Mirrors the
 * pattern shipped with {@code jrpay-identity-service} — see
 * {@code memory/feedback_testcontainers_docker_workaround.md}.
 */
public class RequireRealPostgres implements ExecutionCondition {

    private static final String SKIP_MESSAGE =
            "Skipping integration test: real Postgres unavailable. "
                    + "Either bring up the dev compose stack and set "
                    + "NOTIFICATIONS_TEST_DB_URL=jdbc:postgresql://localhost:5432/notifications, "
                    + "or run on a host where Testcontainers can negotiate with the Docker daemon.";

    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        return NotificationsIntegrationTestBase.ensureDatabaseReady()
                ? ConditionEvaluationResult.enabled("Postgres available")
                : ConditionEvaluationResult.disabled(SKIP_MESSAGE);
    }
}
