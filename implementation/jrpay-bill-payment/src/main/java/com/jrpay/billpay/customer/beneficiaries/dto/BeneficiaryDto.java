package com.jrpay.billpay.customer.beneficiaries.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record BeneficiaryDto(
        String beneficiaryId,
        String name,
        String ibanMasked,
        String bankName,
        Instant lastPaidAt,
        String totalYtd,
        String currency,
        String notes,
        Instant createdAt,
        String status
) {}
