package com.jrpay.billpay.customer.r2p.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record R2pDeferPendingSca(
        String requestId,
        String scheduledPaymentId,
        UUID scaSessionId,
        @JsonProperty("dl_hash") String dlHash,
        Boolean exempt,
        String stepUpToken,
        Map<String, Object> challenge
) {}
