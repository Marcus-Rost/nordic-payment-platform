package com.jrpay.billpay.customer.mandates.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record MandateExecutionDto(
        UUID executionId,
        String mandateId,
        String amount,
        String currency,
        String status,
        Instant attemptedAt,
        Instant settledAt,
        String transactionId,
        String rejectionReason,
        String invoiceReference
) {}
