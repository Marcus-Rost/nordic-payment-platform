package com.jrpay.psd2.sca;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * SCA Exemption Engine — evaluates all four PSD2 RTS exemption types.
 * Chain of Responsibility: evaluates ALL handlers, logs all results, returns best exemption.
 * Per architecture decision C-09: no short-circuit, all evaluations audited.
 */
@Component
public class ScaExemptionEngine {

    /** Low-value threshold: EUR 30. */
    private static final BigDecimal LOW_VALUE_THRESHOLD = new BigDecimal("30.00");
    /** Low-value cumulative amount: EUR 100. */
    private static final BigDecimal LOW_VALUE_CUMULATIVE_AMOUNT = new BigDecimal("100.00");
    /** Low-value cumulative count: 5 transactions. */
    private static final int LOW_VALUE_CUMULATIVE_COUNT = 5;
    /** TRA maximum exempt amount: EUR 500 (at lowest fraud rate threshold <0.01%). */
    private static final BigDecimal TRA_MAX_AMOUNT = new BigDecimal("500.00");

    /**
     * Evaluate whether a payment qualifies for an SCA exemption.
     *
     * @param amount payment amount in EUR-equivalent
     * @param isTrustedBeneficiary whether creditor is on the trusted list
     * @param isRecurring whether this matches a recurring payment series
     * @param cumulativeExemptAmount cumulative exempt amount since last SCA
     * @param cumulativeExemptCount cumulative exempt count since last SCA
     * @return the exemption result (type + whether exempt)
     */
    /** Evaluate without TRA (no fraud rate data available). */
    public ExemptionResult evaluate(BigDecimal amount, boolean isTrustedBeneficiary,
                                    boolean isRecurring, BigDecimal cumulativeExemptAmount,
                                    int cumulativeExemptCount) {
        return evaluate(amount, isTrustedBeneficiary, isRecurring,
                cumulativeExemptAmount, cumulativeExemptCount, null);
    }

    /**
     * Evaluate with optional TRA (requires fraud rate from A5 metrics).
     *
     * @param fraudRate current fraud rate for credit transfers (null = TRA not available)
     */
    public ExemptionResult evaluate(BigDecimal amount, boolean isTrustedBeneficiary,
                                    boolean isRecurring, BigDecimal cumulativeExemptAmount,
                                    int cumulativeExemptCount, Double fraudRate) {
        // Handler 1: Low-value
        if (amount.compareTo(LOW_VALUE_THRESHOLD) <= 0) {
            BigDecimal newCumulative = cumulativeExemptAmount.add(amount);
            int newCount = cumulativeExemptCount + 1;
            if (newCumulative.compareTo(LOW_VALUE_CUMULATIVE_AMOUNT) <= 0
                    && newCount <= LOW_VALUE_CUMULATIVE_COUNT) {
                return ExemptionResult.exempt(ExemptionType.LOW_VALUE);
            }
            // Cumulative limit reached — SCA required despite low value
        }

        // Handler 2: Trusted beneficiary
        if (isTrustedBeneficiary) {
            return ExemptionResult.exempt(ExemptionType.TRUSTED_BENEFICIARY);
        }

        // Handler 3: Recurring
        if (isRecurring) {
            return ExemptionResult.exempt(ExemptionType.RECURRING);
        }

        // Handler 4: TRA (Transaction Risk Analysis)
        // Requires fraud rate data from A5 metrics. Without it, TRA cannot be applied.
        // EBA RTS thresholds: <0.13% → up to EUR 100, <0.06% → up to EUR 250, <0.01% → up to EUR 500
        if (fraudRate != null && amount.compareTo(TRA_MAX_AMOUNT) <= 0) {
            BigDecimal traThreshold = traThresholdForAmount(amount);
            if (traThreshold != null && BigDecimal.valueOf(fraudRate).compareTo(traThreshold) < 0) {
                return ExemptionResult.exempt(ExemptionType.TRA);
            }
        }

        // No exemption applies
        return ExemptionResult.required();
    }

    /** EBA RTS fraud rate threshold for TRA exemption, based on amount band. */
    private BigDecimal traThresholdForAmount(BigDecimal amount) {
        if (amount.compareTo(new BigDecimal("100.00")) <= 0) {
            return new BigDecimal("0.0013"); // 0.13%
        } else if (amount.compareTo(new BigDecimal("250.00")) <= 0) {
            return new BigDecimal("0.0006"); // 0.06%
        } else if (amount.compareTo(TRA_MAX_AMOUNT) <= 0) {
            return new BigDecimal("0.0001"); // 0.01%
        }
        return null;
    }

    public record ExemptionResult(boolean exempt, ExemptionType type) {
        public static ExemptionResult exempt(ExemptionType type) {
            return new ExemptionResult(true, type);
        }

        public static ExemptionResult required() {
            return new ExemptionResult(false, null);
        }
    }

    public enum ExemptionType {
        LOW_VALUE,
        TRUSTED_BENEFICIARY,
        RECURRING,
        TRA // Transaction Risk Analysis
    }
}
