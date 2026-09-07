package com.jrpay.fraud.casework.sanctions.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record SanctionsDecideAck(
        UUID hitId,
        String status,
        Instant decidedAt,
        String decidedBy,
        UUID newFraudCaseId,
        UUID fpCacheEntryId,
        String accountFrozen
) {}
