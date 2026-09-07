package com.jrpay.fraud.casework.sanctions.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record SanctionsListRefreshJobDto(
        UUID runId,
        String status,
        List<String> listIds,
        Instant requestedAt,
        Instant startedAt,
        Instant completedAt,
        String failureReason
) {}
