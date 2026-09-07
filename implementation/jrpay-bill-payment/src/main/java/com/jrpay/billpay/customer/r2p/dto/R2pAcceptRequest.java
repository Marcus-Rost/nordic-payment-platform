package com.jrpay.billpay.customer.r2p.dto;

import jakarta.validation.constraints.NotBlank;

public record R2pAcceptRequest(
        @NotBlank String debtorAccountId,
        String executionMode,
        String vopResult,
        Boolean vopPayerOverride
) {}
