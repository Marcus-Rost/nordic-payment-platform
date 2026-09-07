package com.jrpay.ledger.admin.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.jrpay.ledger.domain.PiiField;
import java.time.Instant;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record RevealPiiAck(
        PiiField field,
        String value,
        long ttlSeconds,
        int extensionsRemaining,
        Instant revealedAt,
        UUID revealId,
        String auditEventId
) {}
