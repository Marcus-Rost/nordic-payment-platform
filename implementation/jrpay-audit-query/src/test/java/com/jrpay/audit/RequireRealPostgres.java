package com.jrpay.audit;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;

public class RequireRealPostgres implements ExecutionCondition {

    private static final String SKIP_MESSAGE =
            "Skipping integration test: real Postgres unavailable. "
                    + "Either bring up the dev compose stack and set "
                    + "AUDIT_TEST_DB_URL=jdbc:postgresql://localhost:5432/audit, "
                    + "or run on a host where Testcontainers can negotiate with the Docker daemon.";

    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        return AuditIntegrationTestBase.ensureDatabaseReady()
                ? ConditionEvaluationResult.enabled("Postgres available")
                : ConditionEvaluationResult.disabled(SKIP_MESSAGE);
    }
}
