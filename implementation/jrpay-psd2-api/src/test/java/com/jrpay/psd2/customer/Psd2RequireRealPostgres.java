package com.jrpay.psd2.customer;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;

public class Psd2RequireRealPostgres implements ExecutionCondition {

    private static final String SKIP_MESSAGE =
            "Skipping customer integration test: real Postgres unavailable. "
                    + "Either bring up the dev compose stack and set "
                    + "PSD2_TEST_DB_URL=jdbc:postgresql://localhost:5432/psd2, "
                    + "or run on a host where Testcontainers can negotiate with Docker.";

    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        return Psd2CustomerIntegrationTestBase.ensureDatabaseReady()
                ? ConditionEvaluationResult.enabled("Postgres available")
                : ConditionEvaluationResult.disabled(SKIP_MESSAGE);
    }
}
