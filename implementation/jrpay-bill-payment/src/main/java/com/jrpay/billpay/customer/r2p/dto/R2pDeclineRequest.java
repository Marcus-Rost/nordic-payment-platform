package com.jrpay.billpay.customer.r2p.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record R2pDeclineRequest(
        @NotNull String reasonCode,
        @Size(max = 280) String reasonNote
) {}
