package com.jrpay.billpay.customer.mandates.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record MandateDetail(
        MandateSummary summary,
        String avtaleId,
        Boolean notifyBeforeDebit,
        Instant createdAt,
        Instant updatedAt,
        Instant revokedAt,
        List<Warning> warnings
) {

    public record Warning(String code, String message) {}
}
