package com.jrpay.fraud.casework.sanctions.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record SanctionsListMetaDto(
        String listId,
        String name,
        String source,
        String version,
        int entryCount,
        Instant lastRefreshAt,
        long staleHours
) {}
