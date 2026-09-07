package com.jrpay.billpay.customer.r2p.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public record R2pDeferRequest(
        @NotBlank String debtorAccountId,
        @NotNull Instant executeAt,
        String executionMode,
        @Size(max = 280) String note
) {}
