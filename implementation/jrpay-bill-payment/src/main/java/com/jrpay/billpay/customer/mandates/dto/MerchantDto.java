package com.jrpay.billpay.customer.mandates.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record MerchantDto(
        String merchantId,
        String avtaleId,
        String displayName,
        String tradingName,
        String iban,
        String bic,
        String logoUrl,
        List<String> acceptedFrequencies,
        Instant registryUpdatedAt
) {}
