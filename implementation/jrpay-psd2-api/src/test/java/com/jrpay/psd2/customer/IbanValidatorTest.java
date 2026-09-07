package com.jrpay.psd2.customer;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class IbanValidatorTest {

    @Test
    void validNorwegianIbanPasses() {
        assertThat(IbanValidator.isValid("NO9386011117947")).isTrue();
        assertThat(IbanValidator.isValid("NO93 8601 1117 947")).isTrue();
    }

    @Test
    void invalidIbanFails() {
        assertThat(IbanValidator.isValid("NO0000000000000")).isFalse();
        assertThat(IbanValidator.isValid(null)).isFalse();
    }

    @Test
    void maskKeepsFirstAndLastFour() {
        assertThat(IbanValidator.mask("NO9386011117947"))
                .startsWith("NO93")
                .endsWith("7947")
                .contains("•");
    }
}
