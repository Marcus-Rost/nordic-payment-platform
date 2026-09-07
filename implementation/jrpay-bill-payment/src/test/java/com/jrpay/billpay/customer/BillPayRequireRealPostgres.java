package com.jrpay.billpay.customer;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;

public class BillPayRequireRealPostgres implements ExecutionCondition {

    private static final String SKIP_MESSAGE =
            "Skipping customer integration test: real Postgres unavailable. "
                    + "Either bring up the dev compose stack and set "
                    + "BILLPAY_TEST_DB_URL=jdbc:postgresql://localhost:5432/billpay, "
                    + "or run on a host where Testcontainers can negotiate with the Docker daemon.";

    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        return BillPayCustomerIntegrationTestBase.ensureDatabaseReady()
                ? ConditionEvaluationResult.enabled("Postgres available")
                : ConditionEvaluationResult.disabled(SKIP_MESSAGE);
    }
}
