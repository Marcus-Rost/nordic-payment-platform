package com.jrpay.psd2.customer.payments.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record PaymentAck(
        String paymentId,
        String status,
        String clearingPath,
        Instant settlementInstant,
        String receiptUrl
) {}
