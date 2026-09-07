package com.jrpay.psd2.customer.payments.dto;

import jakarta.validation.constraints.NotBlank;

public record PaymentRequestDto(
        @NotBlank String debtorAccountId,
        @NotBlank String creditorIban,
        @NotBlank String creditorName,
        @NotBlank String amount,
        @NotBlank String currency,
        String reference,
        String description,
        String executionMode,
        String vopResult,
        Boolean vopPayerOverride
) {}
