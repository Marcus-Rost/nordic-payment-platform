package com.jrpay.billpay.customer.r2p.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.time.LocalDate;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record R2pSummary(
        String requestId,
        String merchantName,
        Boolean merchantVerified,
        String merchantLogoUrl,
        String amount,
        String currency,
        LocalDate dueDate,
        String status,
        String reference,
        Instant receivedAt,
        Instant updatedAt,
        String requestedExecutionMode
) {}
