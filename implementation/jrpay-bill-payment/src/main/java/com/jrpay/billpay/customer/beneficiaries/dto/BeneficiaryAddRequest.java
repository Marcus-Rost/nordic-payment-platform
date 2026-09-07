package com.jrpay.billpay.customer.beneficiaries.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record BeneficiaryAddRequest(
        @NotBlank @Size(max = 140) String name,
        @NotBlank String iban,
        @Size(max = 140) String notes,
        String vopResult,
        Boolean vopPayerOverride
) {}
