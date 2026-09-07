package com.jrpay.domain.money;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

class MonetaryAmountTest {

    @Test
    @DisplayName("Create from string — correct scale applied")
    void createFromString() {
        MonetaryAmount amount = MonetaryAmount.of("19.99", Currency.NOK);
        assertEquals(new BigDecimal("19.99"), amount.getAmount());
        assertEquals(Currency.NOK, amount.getCurrency());
    }

    @Test
    @DisplayName("Create from string rounds to currency scale")
    void createRoundsToScale() {
        // 19.999 rounded to 2 decimals with HALF_EVEN = 20.00
        MonetaryAmount amount = MonetaryAmount.of("19.999", Currency.EUR);
        assertEquals(new BigDecimal("20.00"), amount.getAmount());
    }

    @Test
    @DisplayName("Negative amount is rejected")
    void negativeRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> MonetaryAmount.of("-1.00", Currency.NOK));
    }

    @Test
    @DisplayName("Zero is valid")
    void zeroIsValid() {
        MonetaryAmount zero = MonetaryAmount.zero(Currency.EUR);
        assertTrue(zero.isZero());
        assertEquals(new BigDecimal("0.00"), zero.getAmount());
    }

    @Test
    @DisplayName("Add same currency")
    void addSameCurrency() {
        MonetaryAmount a = MonetaryAmount.of("100.00", Currency.NOK);
        MonetaryAmount b = MonetaryAmount.of("50.50", Currency.NOK);
        MonetaryAmount result = a.add(b);
        assertEquals(MonetaryAmount.of("150.50", Currency.NOK), result);
    }

    @Test
    @DisplayName("Add different currencies throws")
    void addDifferentCurrenciesThrows() {
        MonetaryAmount nok = MonetaryAmount.of("100.00", Currency.NOK);
        MonetaryAmount eur = MonetaryAmount.of("50.00", Currency.EUR);
        assertThrows(IllegalArgumentException.class, () -> nok.add(eur));
    }

    @Test
    @DisplayName("Subtract resulting in zero")
    void subtractToZero() {
        MonetaryAmount a = MonetaryAmount.of("100.00", Currency.NOK);
        MonetaryAmount b = MonetaryAmount.of("100.00", Currency.NOK);
        assertTrue(a.subtract(b).isZero());
    }

    @Test
    @DisplayName("Subtract resulting in negative throws")
    void subtractNegativeThrows() {
        MonetaryAmount a = MonetaryAmount.of("50.00", Currency.NOK);
        MonetaryAmount b = MonetaryAmount.of("100.00", Currency.NOK);
        assertThrows(IllegalArgumentException.class, () -> a.subtract(b));
    }

    @Test
    @DisplayName("Equals uses compareTo (2.0 equals 2.00)")
    void equalsUsesCompareTo() {
        MonetaryAmount a = MonetaryAmount.of("2.00", Currency.EUR);
        MonetaryAmount b = MonetaryAmount.of("2.0", Currency.EUR);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    @DisplayName("Different currencies are not equal")
    void differentCurrenciesNotEqual() {
        MonetaryAmount nok = MonetaryAmount.of("100.00", Currency.NOK);
        MonetaryAmount eur = MonetaryAmount.of("100.00", Currency.EUR);
        assertNotEquals(nok, eur);
    }

    @Test
    @DisplayName("isGreaterThanOrEqual works correctly")
    void greaterThanOrEqual() {
        MonetaryAmount hundred = MonetaryAmount.of("100.00", Currency.NOK);
        MonetaryAmount fifty = MonetaryAmount.of("50.00", Currency.NOK);
        assertTrue(hundred.isGreaterThanOrEqual(fifty));
        assertTrue(hundred.isGreaterThanOrEqual(hundred));
        assertFalse(fifty.isGreaterThanOrEqual(hundred));
    }

    @Test
    @DisplayName("toString produces readable format")
    void toStringReadable() {
        MonetaryAmount amount = MonetaryAmount.of("1234.56", Currency.EUR);
        assertEquals("1234.56 EUR", amount.toString());
    }

    @Test
    @DisplayName("toPlainString produces no scientific notation")
    void toPlainStringNoScientificNotation() {
        MonetaryAmount large = MonetaryAmount.of("99999999.99", Currency.NOK);
        assertEquals("99999999.99", large.toPlainString());
    }
}
