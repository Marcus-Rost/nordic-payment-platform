package com.jrpay.ledger.admin;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Skips integration tests if no Postgres is reachable. Mirrors the
 * identity-service {@code RequireRealPostgres} extension.
 */
public class LedgerRequireRealPostgres implements ExecutionCondition {

    private static final String SKIP_MESSAGE =
            "Skipping admin integration test: real Postgres unavailable. "
                    + "Either bring up the dev compose stack and set "
                    + "LEDGER_TEST_DB_URL=jdbc:postgresql://localhost:5432/ledger, "
                    + "or run on a host where Testcontainers can negotiate with the Docker daemon.";

    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        return LedgerAdminIntegrationTestBase.ensureDatabaseReady()
                ? ConditionEvaluationResult.enabled("Postgres available")
                : ConditionEvaluationResult.disabled(SKIP_MESSAGE);
    }
}
