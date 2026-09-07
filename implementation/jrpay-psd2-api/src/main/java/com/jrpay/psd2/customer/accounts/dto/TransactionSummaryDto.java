package com.jrpay.psd2.customer.accounts.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record TransactionSummaryDto(
        String transactionId,
        String accountId,
        Instant postedAt,
        String direction,
        String amount,
        String currency,
        String status,
        String counterpartyName,
        String counterpartyIbanMasked,
        String reference,
        String description
) {}
