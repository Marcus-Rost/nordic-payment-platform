package com.jrpay.billpay.customer.mandates.dto;

import java.time.Instant;

public record MandateUpdateRequest(
        String maxAmount,
        String status,
        Instant resumeAt,
        Boolean notifyBeforeDebit
) {}
