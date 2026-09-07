package com.jrpay.psd2.customer.accounts.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record AccountDetailDto(
        AccountSummaryDto summary,
        String bic,
        Instant openedAt,
        Instant closedAt,
        Instant updatedAt
) {}
