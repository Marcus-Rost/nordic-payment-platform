package com.jrpay.ledger.admin.dto;

import com.jrpay.ledger.domain.PiiField;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record RevealPiiRequest(
        @NotNull PiiField field,
        @NotNull @Size(min = 20, max = 2000) String justificationNotes,
        @NotNull Boolean confirmedBusinessNeed
) {}
