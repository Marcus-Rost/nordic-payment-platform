package com.jrpay.fraud.casework.sanctions.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record SanctionsHitDetailDto(
        SanctionsHitSummaryDto summary,
        String creditorIban,
        String reference,
        Map<String, Object> matchedEntity,
        Decision decision
) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Decision(
            String decidedBy,
            Instant decidedAt,
            String decision,
            String notesSummary,
            String notesHash
    ) {}
}
