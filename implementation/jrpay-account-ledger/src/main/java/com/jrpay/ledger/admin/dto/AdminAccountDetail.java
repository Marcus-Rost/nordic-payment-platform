package com.jrpay.ledger.admin.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record AdminAccountDetail(
        AdminAccountSummary summary,
        String iban,
        String bic,
        String reservedAmount,
        List<ReservationItem> reservations,
        Instant openedAt,
        Instant closedAt,
        FreezeDetail freezeDetail,
        String holderAddressMasked,
        String fodselsnummerMasked
) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ReservationItem(
            String paymentId,
            String amount,
            String currency,
            Instant createdAt
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FreezeDetail(
            String reason,
            String reference,
            Instant appliedAt,
            String appliedBy,
            String notesSummary
    ) {}
}
