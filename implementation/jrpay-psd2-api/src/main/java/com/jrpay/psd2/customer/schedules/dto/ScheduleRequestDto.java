package com.jrpay.psd2.customer.schedules.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;

public record ScheduleRequestDto(
        @NotBlank String debtorAccountId,
        @NotBlank String creditorIban,
        @NotBlank String creditorName,
        @NotBlank String amount,
        @NotBlank String currency,
        String reference,
        @NotNull Instant executeAt,
        String executionMode,
        String note
) {}
