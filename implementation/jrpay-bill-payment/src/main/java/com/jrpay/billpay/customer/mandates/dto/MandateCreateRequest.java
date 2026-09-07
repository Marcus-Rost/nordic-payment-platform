package com.jrpay.billpay.customer.mandates.dto;

import jakarta.validation.constraints.NotBlank;
import java.time.LocalDate;

public record MandateCreateRequest(
        @NotBlank String merchantId,
        @NotBlank String debtorAccountId,
        @NotBlank String maxAmount,
        @NotBlank String currency,
        String frequency,
        LocalDate startDate,
        LocalDate endDate,
        Boolean notifyBeforeDebit
) {}
