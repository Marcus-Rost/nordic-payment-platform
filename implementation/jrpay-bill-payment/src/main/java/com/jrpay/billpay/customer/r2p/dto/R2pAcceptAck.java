package com.jrpay.billpay.customer.r2p.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record R2pAcceptAck(
        String requestId,
        String paymentId,
        String status,
        String clearingPath,
        Instant settlementInstant,
        String receiptUrl
) {}
