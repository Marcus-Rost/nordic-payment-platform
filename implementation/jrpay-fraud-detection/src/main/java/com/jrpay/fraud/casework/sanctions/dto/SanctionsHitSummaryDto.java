package com.jrpay.fraud.casework.sanctions.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record SanctionsHitSummaryDto(
        UUID hitId,
        UUID paymentId,
        String matchedEntityName,
        String matchedListId,
        String matchScore,
        String creditorIbanMasked,
        String creditorName,
        String amount,
        String currency,
        String status,
        UUID assignee,
        Instant slaDeadline,
        boolean slaBreached,
        Instant createdAt
) {}
