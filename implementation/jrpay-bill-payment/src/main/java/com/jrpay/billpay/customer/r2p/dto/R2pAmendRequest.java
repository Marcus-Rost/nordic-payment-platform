package com.jrpay.billpay.customer.r2p.dto;

import jakarta.validation.constraints.Size;
import java.time.LocalDate;

public record R2pAmendRequest(
        String proposedAmount,
        LocalDate proposedDueDate,
        @Size(max = 280) String reason
) {}
