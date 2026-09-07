package com.jrpay.billpay.customer.beneficiaries.dto;

import jakarta.validation.constraints.Size;

public record BeneficiaryUpdateRequest(
        @Size(max = 140) String notes
) {}
