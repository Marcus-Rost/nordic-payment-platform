package com.jrpay.fraud.casework.fraud.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record FraudDecideRequest(
        @NotNull String decision,
        @NotNull @Size(min = 50, max = 2000) String notes,
        @NotNull Boolean confirmedCriteria,
        String freezeReason
) {}
