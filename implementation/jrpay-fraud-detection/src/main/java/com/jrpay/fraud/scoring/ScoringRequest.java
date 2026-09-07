package com.jrpay.fraud.scoring;

import com.jrpay.domain.payment.VoPResult;

import java.math.BigDecimal;

/**
 * Input to the scoring pipeline — represents a payment to be scored.
 */
public record ScoringRequest(
        String transactionId,
        String debtorAccountId,
        String creditorAccountId,
        String creditorName,
        BigDecimal amount,
        String currency,
        String paymentMethod, // INSTANT or BATCH
        VoPResult vopResult,
        boolean vopPayerOverride
) {}
