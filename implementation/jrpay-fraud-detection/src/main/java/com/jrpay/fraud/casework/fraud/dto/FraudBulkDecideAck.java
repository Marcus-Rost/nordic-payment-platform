package com.jrpay.fraud.casework.fraud.dto;

import java.util.List;
import java.util.UUID;

public record FraudBulkDecideAck(
        int total,
        int succeeded,
        int failed,
        List<Result> results
) {

    public record Result(UUID caseId, String outcome, String errorKey) {}
}
