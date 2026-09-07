package com.jrpay.fraud.casework.fraud.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record FraudCaseSummaryDto(
        UUID caseId,
        String status,
        String score,
        String scoreBand,
        String amount,
        String currency,
        String holderNameMasked,
        String debtorAccountId,
        String creditorIbanMasked,
        List<String> triggeredRules,
        UUID assignee,
        Instant createdAt,
        Instant slaDeadline,
        boolean slaBreached,
        UUID paymentId
) {}
