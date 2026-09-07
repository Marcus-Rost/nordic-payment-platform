package com.jrpay.ledger.admin.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record AdminTransactionSummary(
        String transactionId,
        Instant postedAt,
        String direction,
        String amount,
        String currency,
        String status,
        String counterpartyNameMasked,
        String counterpartyIbanMasked,
        String reference,
        String rejectionReason
) {}
