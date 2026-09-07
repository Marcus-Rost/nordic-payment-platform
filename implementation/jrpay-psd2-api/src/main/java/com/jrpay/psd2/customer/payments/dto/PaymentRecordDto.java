package com.jrpay.psd2.customer.payments.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record PaymentRecordDto(
        String paymentId,
        String debtorAccountId,
        String creditorIban,
        String creditorName,
        String amount,
        String currency,
        String reference,
        String description,
        String executionMode,
        String status,
        String rejectionReason,
        Instant createdAt,
        Instant updatedAt,
        Instant settledAt,
        Instant cancelledAt
) {}
