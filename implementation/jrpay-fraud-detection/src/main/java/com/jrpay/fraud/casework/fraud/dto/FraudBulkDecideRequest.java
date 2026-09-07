package com.jrpay.fraud.casework.fraud.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

public record FraudBulkDecideRequest(
        @NotEmpty @Size(max = 50) List<UUID> caseIds,
        @NotNull String decision,
        @NotNull @Size(min = 50, max = 2000) String notes,
        @NotNull Boolean confirmedCriteria,
        String freezeReason
) {}
