package com.jrpay.ledger.admin.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record RevealPiiPendingApproval(
        boolean requiresSecondApproval,
        String approvalRef,
        Instant expiresAt,
        Instant rateLimitWindowResetAt
) {}
