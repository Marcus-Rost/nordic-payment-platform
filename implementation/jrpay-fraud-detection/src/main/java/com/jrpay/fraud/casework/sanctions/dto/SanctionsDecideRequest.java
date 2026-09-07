package com.jrpay.fraud.casework.sanctions.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record SanctionsDecideRequest(
        @NotNull String decision,
        @NotNull @Size(min = 50, max = 2000) String notes,
        @NotNull Boolean confirmedCriteria,
        Boolean addToFpCache
) {}
