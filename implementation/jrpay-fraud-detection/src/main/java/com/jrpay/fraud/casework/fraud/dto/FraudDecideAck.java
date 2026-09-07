package com.jrpay.fraud.casework.fraud.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record FraudDecideAck(
        UUID caseId,
        String status,
        Instant decidedAt,
        String decidedBy,
        List<UUID> affectedPayments,
        AccountFrozen accountFrozen
) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AccountFrozen(
            String accountId,
            String freezeReason,
            String freezeReference
    ) {}
}
