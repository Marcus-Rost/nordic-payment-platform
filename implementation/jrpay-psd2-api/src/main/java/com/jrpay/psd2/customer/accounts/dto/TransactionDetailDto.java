package com.jrpay.psd2.customer.accounts.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record TransactionDetailDto(
        TransactionSummaryDto summary,
        String counterpartyIban,
        String rejectionReason,
        Instant settledAt
) {}
