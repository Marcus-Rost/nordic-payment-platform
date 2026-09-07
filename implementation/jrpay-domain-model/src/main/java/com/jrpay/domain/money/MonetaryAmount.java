package com.jrpay.domain.money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Immutable monetary amount with currency. Core domain primitive.
 *
 * <p>Invariants enforced at construction:
 * <ul>
 *   <li>Amount is non-negative</li>
 *   <li>Scale matches the currency's ISO 4217 minor units</li>
 *   <li>Currency is never null</li>
 * </ul>
 *
 * <p>Arithmetic operations enforce currency match — cannot add EUR to NOK.
 * All rounding uses HALF_EVEN (Banker's Rounding) by default.
 */
public final class MonetaryAmount implements Comparable<MonetaryAmount> {

    public static final RoundingMode DEFAULT_ROUNDING = RoundingMode.HALF_EVEN;

    private final BigDecimal amount;
    private final Currency currency;

    private MonetaryAmount(BigDecimal amount, Currency currency) {
        this.amount = amount;
        this.currency = currency;
    }

    /**
     * Create from string amount (safe — no floating-point representation error).
     *
     * @throws IllegalArgumentException if amount is negative or currency is null
     */
    public static MonetaryAmount of(String amount, Currency currency) {
        Objects.requireNonNull(currency, "Currency must not be null");
        Objects.requireNonNull(amount, "Amount must not be null");

        BigDecimal value = new BigDecimal(amount);
        return of(value, currency);
    }

    /**
     * Create from BigDecimal amount.
     *
     * @throws IllegalArgumentException if amount is negative
     */
    public static MonetaryAmount of(BigDecimal amount, Currency currency) {
        Objects.requireNonNull(currency, "Currency must not be null");
        Objects.requireNonNull(amount, "Amount must not be null");

        if (amount.signum() < 0) {
            throw new IllegalArgumentException(
                    "MonetaryAmount cannot be negative: " + amount + " " + currency);
        }

        BigDecimal scaled = amount.setScale(currency.getMinorUnits(), DEFAULT_ROUNDING);
        return new MonetaryAmount(scaled, currency);
    }

    /** Zero amount in the given currency. */
    public static MonetaryAmount zero(Currency currency) {
        return of(BigDecimal.ZERO, currency);
    }

    /**
     * Add two amounts. Currencies must match.
     *
     * @throws IllegalArgumentException if currencies differ
     */
    public MonetaryAmount add(MonetaryAmount other) {
        requireSameCurrency(other);
        return new MonetaryAmount(
                this.amount.add(other.amount).setScale(currency.getMinorUnits(), DEFAULT_ROUNDING),
                this.currency);
    }

    /**
     * Subtract. Result must be non-negative.
     *
     * @throws IllegalArgumentException if currencies differ or result would be negative
     */
    public MonetaryAmount subtract(MonetaryAmount other) {
        requireSameCurrency(other);
        BigDecimal result = this.amount.subtract(other.amount);
        if (result.signum() < 0) {
            throw new IllegalArgumentException(
                    "Subtraction would result in negative amount: "
                            + this.amount + " - " + other.amount + " " + currency);
        }
        return new MonetaryAmount(
                result.setScale(currency.getMinorUnits(), DEFAULT_ROUNDING),
                this.currency);
    }

    /** Check if this amount is greater than or equal to other. Currencies must match. */
    public boolean isGreaterThanOrEqual(MonetaryAmount other) {
        requireSameCurrency(other);
        return this.amount.compareTo(other.amount) >= 0;
    }

    /** Check if this amount is zero. */
    public boolean isZero() {
        return amount.signum() == 0;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public Currency getCurrency() {
        return currency;
    }

    /**
     * Serialize to string for Avro/Kafka (BigDecimal.toPlainString — no scientific notation).
     */
    public String toPlainString() {
        return amount.toPlainString();
    }

    private void requireSameCurrency(MonetaryAmount other) {
        if (this.currency != other.currency) {
            throw new IllegalArgumentException(
                    "Cannot combine amounts in different currencies: "
                            + this.currency + " and " + other.currency
                            + ". Explicit conversion required.");
        }
    }

    @Override
    public int compareTo(MonetaryAmount other) {
        requireSameCurrency(other);
        return this.amount.compareTo(other.amount);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        MonetaryAmount that = (MonetaryAmount) o;
        // Use compareTo for BigDecimal (2.00 equals 2.0)
        return currency == that.currency && amount.compareTo(that.amount) == 0;
    }

    @Override
    public int hashCode() {
        // stripTrailingZeros ensures 2.00 and 2.0 have the same hashCode
        return Objects.hash(amount.stripTrailingZeros(), currency);
    }

    @Override
    public String toString() {
        return amount.toPlainString() + " " + currency;
    }
}
