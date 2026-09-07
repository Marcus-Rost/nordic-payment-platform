package com.jrpay.billpay.customer.r2p.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record R2pDetail(
        R2pSummary summary,
        String merchantId,
        String description,
        String invoicePdfUrl,
        Instant invoicePdfExpiresAt,
        String creditorIban,
        String creditorName,
        Boolean amendmentAllowed,
        String paymentId,
        String scheduledPaymentId,
        String declineReason,
        String declineNote,
        Boolean amendmentRejected,
        Amendment amendment,
        String replacedBy,
        String replaces,
        List<HistoryEntry> history
) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Amendment(
            Instant proposedAt,
            String proposedAmount,
            LocalDate proposedDueDate,
            String reason
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record HistoryEntry(
            Instant at,
            String status,
            String actor,
            String note
    ) {}
}
