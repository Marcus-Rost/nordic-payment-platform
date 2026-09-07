package com.jrpay.billpay.customer.mandates.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.time.LocalDate;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record MandateSummary(
        String mandateId,
        String merchantId,
        String merchantName,
        String merchantLogoUrl,
        String debtorAccountId,
        String maxAmount,
        String currency,
        String frequency,
        String status,
        LocalDate startDate,
        LocalDate endDate,
        Instant lastExecutedAt,
        Instant nextExpectedAt,
        Instant resumeAt
) {}
