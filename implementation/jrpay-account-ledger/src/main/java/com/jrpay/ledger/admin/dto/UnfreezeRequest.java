package com.jrpay.ledger.admin.dto;

import com.jrpay.ledger.domain.UnfreezeReason;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record UnfreezeRequest(
        @NotNull UnfreezeReason reason,
        @NotNull @Size(min = 50, max = 2000) String notes,
        @NotNull Boolean confirmedCriteria
) {}
