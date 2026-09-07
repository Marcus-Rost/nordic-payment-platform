package com.jrpay.fraud.casework;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;

public class CaseworkRequireRealPostgres implements ExecutionCondition {

    private static final String SKIP_MESSAGE =
            "Skipping casework integration test: real Postgres unavailable. "
                    + "Either bring up the dev compose stack and set "
                    + "CASEWORK_TEST_DB_URL=jdbc:postgresql://localhost:5432/casework, "
                    + "or run on a host where Testcontainers can negotiate with Docker.";

    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        return CaseworkIntegrationTestBase.ensureDatabaseReady()
                ? ConditionEvaluationResult.enabled("Postgres available")
                : ConditionEvaluationResult.disabled(SKIP_MESSAGE);
    }
}
