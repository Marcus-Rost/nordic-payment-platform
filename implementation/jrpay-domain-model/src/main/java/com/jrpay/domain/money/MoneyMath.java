package com.jrpay.domain.money;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Safe arithmetic for monetary calculations.
 * All operations enforce explicit rounding. Bare BigDecimal.divide() is prohibited
 * (enforced by ArchUnit rule — no direct BigDecimal.divide outside this class).
 */
public final class MoneyMath {

    /** Default rounding: HALF_EVEN (Banker's Rounding) — IEEE 754 default, statistically unbiased. */
    public static final RoundingMode DEFAULT_ROUNDING = RoundingMode.HALF_EVEN;

    /** Extra precision digits for intermediate calculations before final rounding. */
    public static final int INTERMEDIATE_SCALE_EXTRA = 4;

    private MoneyMath() {
        // utility class
    }

    /**
     * Divide with explicit rounding. Uses intermediate precision (currency scale + 4 digits)
     * then rounds to final currency scale.
     */
    public static MonetaryAmount divide(MonetaryAmount amount, BigDecimal divisor, Currency resultCurrency) {
        if (divisor.signum() == 0) {
            throw new ArithmeticException("Division by zero");
        }
        int intermediateScale = resultCurrency.getMinorUnits() + INTERMEDIATE_SCALE_EXTRA;
        BigDecimal result = amount.getAmount()
                .divide(divisor, intermediateScale, DEFAULT_ROUNDING)
                .setScale(resultCurrency.getMinorUnits(), DEFAULT_ROUNDING);
        return MonetaryAmount.of(result, resultCurrency);
    }

    /**
     * Multiply amount by a factor (e.g., exchange rate, percentage).
     * Result rounded to the target currency's scale.
     */
    public static MonetaryAmount multiply(MonetaryAmount amount, BigDecimal factor, Currency resultCurrency) {
        int intermediateScale = resultCurrency.getMinorUnits() + INTERMEDIATE_SCALE_EXTRA;
        BigDecimal result = amount.getAmount()
                .multiply(factor)
                .setScale(intermediateScale, DEFAULT_ROUNDING)
                .setScale(resultCurrency.getMinorUnits(), DEFAULT_ROUNDING);
        return MonetaryAmount.of(result, resultCurrency);
    }

    /**
     * Sum a collection of amounts. All must have the same currency.
     */
    public static MonetaryAmount sum(Iterable<MonetaryAmount> amounts, Currency currency) {
        MonetaryAmount total = MonetaryAmount.zero(currency);
        for (MonetaryAmount amount : amounts) {
            total = total.add(amount);
        }
        return total;
    }
}
