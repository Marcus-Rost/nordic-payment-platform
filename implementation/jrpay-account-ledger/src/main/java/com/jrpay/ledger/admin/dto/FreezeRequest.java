package com.jrpay.ledger.admin.dto;

import com.jrpay.ledger.domain.FreezeReason;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record FreezeRequest(
        @NotNull FreezeReason reason,
        @NotNull @Size(min = 50, max = 2000) String notes,
        @NotNull Boolean confirmedCriteria,
        String freezeReference
) {}
