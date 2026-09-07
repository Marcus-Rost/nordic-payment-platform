package com.jrpay.ledger.admin.dto;

import com.jrpay.ledger.domain.AccountType;
import com.jrpay.domain.money.Currency;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateAccountRequest(
        @NotBlank String customerId,
        @NotNull AccountType type,
        @NotNull Currency currency,
        @Size(max = 140) String nickname,
        @Valid InitialDeposit initialDeposit,
        @NotNull @Size(min = 50, max = 2000) String notes,
        @NotNull Boolean confirmedCriteria
) {

    public record InitialDeposit(
            @NotBlank String amount,
            @NotBlank String sourceAccountId,
            @Size(max = 140) String reference
    ) {}
}
