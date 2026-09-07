package com.jrpay.fraud.casework;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MaskingTest {

    @Test
    void ibanMaskKeepsFirstAndLastFour() {
        assertThat(Masking.iban("NO9386011117947"))
                .startsWith("NO93")
                .endsWith("7947")
                .contains("•");
    }

    @Test
    void holderNameMasksAfterFirstWord() {
        assertThat(Masking.holderName("Ola Nordmann")).isEqualTo("Ola N*******");
    }

    @Test
    void nullSafe() {
        assertThat(Masking.iban(null)).isNull();
        assertThat(Masking.holderName(null)).isNull();
    }
}
