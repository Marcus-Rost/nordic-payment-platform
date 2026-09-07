package com.jrpay.billpay.customer;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class IbanValidatorTest {

    @Test
    void validNorwegianIbanPassesMod97() {
        assertThat(IbanValidator.isValid("NO9386011117947")).isTrue();
        assertThat(IbanValidator.isValid("NO93 8601 1117 947")).isTrue();
    }

    @Test
    void invalidIbanFails() {
        assertThat(IbanValidator.isValid("NO0000000000000")).isFalse();
        assertThat(IbanValidator.isValid("ZZ123")).isFalse();
        assertThat(IbanValidator.isValid(null)).isFalse();
    }

    @Test
    void compactStripsSpacesAndUppercases() {
        assertThat(IbanValidator.compact("no93 8601 1117 947"))
                .isEqualTo("NO9386011117947");
    }
}
