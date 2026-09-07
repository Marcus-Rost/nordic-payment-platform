package com.jrpay.fraud.casework.sanctions.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record FpCacheEntryDto(
        UUID entryId,
        String matchedEntityName,
        String matchedListId,
        String creditorIbanMasked,
        String notesSummary,
        UUID addedBy,
        Instant createdAt,
        Instant expiresAt
) {}
