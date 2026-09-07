package com.jrpay.psd2.customer.accounts.dto;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

public record StatementRequestDto(
        @NotNull LocalDate from,
        @NotNull LocalDate to,
        String format
) {}
