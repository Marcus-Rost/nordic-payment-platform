package com.jrpay.billpay.customer.beneficiaries.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record BeneficiaryDetail(
        BeneficiaryDto summary,
        String iban,
        String bic,
        Instant activatedAt,
        Integer paymentsCountYtd,
        String vopAtCreation,
        Boolean vopPayerOverrideAtCreation
) {}
