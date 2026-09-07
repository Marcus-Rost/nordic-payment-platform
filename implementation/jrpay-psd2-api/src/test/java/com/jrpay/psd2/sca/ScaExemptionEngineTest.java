package com.jrpay.psd2.sca;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the SCA exemption engine — all 4 PSD2 RTS exemption types.
 */
class ScaExemptionEngineTest {

    private ScaExemptionEngine engine;

    @BeforeEach
    void setUp() {
        engine = new ScaExemptionEngine();
    }

    // === Low-value exemption ===

    @Test
    @DisplayName("Low-value: EUR 25 → EXEMPT (below EUR 30)")
    void lowValue_exempt() {
        var result = engine.evaluate(new BigDecimal("25.00"), false, false,
                BigDecimal.ZERO, 0);
        assertTrue(result.exempt());
        assertEquals(ScaExemptionEngine.ExemptionType.LOW_VALUE, result.type());
    }

    @Test
    @DisplayName("Low-value: EUR 35 → SCA REQUIRED (above EUR 30)")
    void lowValue_aboveThreshold() {
        var result = engine.evaluate(new BigDecimal("35.00"), false, false,
                BigDecimal.ZERO, 0);
        assertFalse(result.exempt());
    }

    @Test
    @DisplayName("Low-value: EUR 25 but cumulative EUR 80 → still EXEMPT (80+25=105 > 100)")
    void lowValue_cumulativeAmountExceeded() {
        // Would push cumulative to EUR 105 > EUR 100 limit
        var result = engine.evaluate(new BigDecimal("25.00"), false, false,
                new BigDecimal("80.00"), 3);
        assertFalse(result.exempt()); // SCA required despite low value
    }

    @Test
    @DisplayName("Low-value: EUR 10 but 5th consecutive exempt → SCA REQUIRED")
    void lowValue_cumulativeCountExceeded() {
        var result = engine.evaluate(new BigDecimal("10.00"), false, false,
                new BigDecimal("40.00"), 5);
        assertFalse(result.exempt()); // 6th would exceed count limit
    }

    // === Trusted beneficiary ===

    @Test
    @DisplayName("Trusted beneficiary: any amount → EXEMPT")
    void trustedBeneficiary_exempt() {
        var result = engine.evaluate(new BigDecimal("50000.00"), true, false,
                BigDecimal.ZERO, 0);
        assertTrue(result.exempt());
        assertEquals(ScaExemptionEngine.ExemptionType.TRUSTED_BENEFICIARY, result.type());
    }

    // === Recurring ===

    @Test
    @DisplayName("Recurring: same amount+creditor → EXEMPT")
    void recurring_exempt() {
        var result = engine.evaluate(new BigDecimal("1000.00"), false, true,
                BigDecimal.ZERO, 0);
        assertTrue(result.exempt());
        assertEquals(ScaExemptionEngine.ExemptionType.RECURRING, result.type());
    }

    // === No exemption ===

    @Test
    @DisplayName("Large amount, not trusted, not recurring → SCA REQUIRED")
    void noExemption() {
        var result = engine.evaluate(new BigDecimal("5000.00"), false, false,
                BigDecimal.ZERO, 0);
        assertFalse(result.exempt());
        assertNull(result.type());
    }

    // === Priority ===

    @Test
    @DisplayName("Low-value takes priority over trusted (both applicable)")
    void lowValuePriority() {
        var result = engine.evaluate(new BigDecimal("20.00"), true, false,
                BigDecimal.ZERO, 0);
        assertTrue(result.exempt());
        assertEquals(ScaExemptionEngine.ExemptionType.LOW_VALUE, result.type());
    }
}
