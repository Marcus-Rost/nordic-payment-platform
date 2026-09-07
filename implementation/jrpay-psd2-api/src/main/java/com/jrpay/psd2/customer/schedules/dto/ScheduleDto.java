package com.jrpay.psd2.customer.schedules.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ScheduleDto(
        String scheduleId,
        String debtorAccountId,
        String creditorIban,
        String creditorName,
        String amount,
        String currency,
        String reference,
        Instant executeAt,
        String executionMode,
        String status,
        String executedPaymentId,
        Instant cancelledAt,
        Instant executedAt,
        String failureReason,
        String note,
        Instant createdAt,
        Instant updatedAt
) {}
