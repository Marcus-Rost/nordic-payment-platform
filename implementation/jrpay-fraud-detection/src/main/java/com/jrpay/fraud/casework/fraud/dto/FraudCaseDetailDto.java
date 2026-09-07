package com.jrpay.fraud.casework.fraud.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record FraudCaseDetailDto(
        FraudCaseSummaryDto summary,
        Transaction transaction,
        Map<String, Object> scoreBreakdown,
        List<Timeline> timeline,
        List<HeldPayment> heldPayments,
        Decision decision
) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Transaction(
            UUID paymentId,
            String debtorIbanMasked,
            String creditorIban,
            String creditorName,
            String amount,
            String currency,
            String reference,
            String executionMode,
            Instant initiatedAt
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Timeline(
            Instant at,
            String type,
            String actor,
            String note
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record HeldPayment(
            UUID paymentId,
            String amount,
            String currency,
            String status
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Decision(
            String decidedBy,
            Instant decidedAt,
            String decision,
            String notesSummary,
            String notesHash
    ) {}
}
