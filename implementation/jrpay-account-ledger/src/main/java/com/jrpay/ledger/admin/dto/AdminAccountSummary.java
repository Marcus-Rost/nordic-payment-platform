package com.jrpay.ledger.admin.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.jrpay.ledger.domain.AccountType;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record AdminAccountSummary(
        String accountId,
        String ibanMasked,
        AccountType type,
        String currency,
        String status,
        String holderNameMasked,
        String customerId,
        String bookedBalance,
        String availableBalance,
        String freezeReason
) {}
