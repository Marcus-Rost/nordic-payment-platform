package com.jrpay.psd2.customer.accounts.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record AccountSummaryDto(
        String accountId,
        String iban,
        String type,
        String currency,
        String bookedBalance,
        String reservedAmount,
        String availableBalance,
        String status,
        String nickname,
        boolean primary
) {}
