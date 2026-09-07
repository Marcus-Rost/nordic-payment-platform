package com.jrpay.psd2.customer.accounts.dto;

public record BalancesDto(
        String accountId,
        String currency,
        String bookedBalance,
        String reservedAmount,
        String availableBalance
) {}
