package com.jrpay.fraud.casework.fraud.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record FraudAssignRequest(
        @NotBlank String assignee,
        @Size(max = 280) String note
) {}
