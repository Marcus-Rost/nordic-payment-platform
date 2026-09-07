package com.jrpay.psd2.customer.accounts.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record StatementResponseDto(
        UUID requestId,
        String status,
        String downloadUrl,
        Instant expiresAt
) {}
