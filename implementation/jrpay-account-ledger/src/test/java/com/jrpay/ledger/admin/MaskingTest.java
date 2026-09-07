package com.jrpay.ledger.admin;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MaskingTest {

    @Test
    void holderNameKeepsFirstWordAndInitialOfSecond() {
        assertThat(Masking.holderName("Ola Nordmann")).isEqualTo("Ola N*******");
        assertThat(Masking.holderName("Kari Hansen Berg")).isEqualTo("Kari H*****");
    }

    @Test
    void holderNameSingleWordMasksAfterFirstTwoChars() {
        assertThat(Masking.holderName("Ola")).isEqualTo("Ol*");
    }

    @Test
    void holderNameNullAndEmpty() {
        assertThat(Masking.holderName(null)).isNull();
        assertThat(Masking.holderName("")).isEmpty();
    }

    @Test
    void ibanGroupsBulletsInFours() {
        // NO9386011117947 has 15 chars -> first4 + 7-char middle + last4
        assertThat(Masking.iban("NO9386011117947"))
                .isEqualTo("NO93 •••• ••• 7947");
    }

    @Test
    void ibanShortPassesThrough() {
        assertThat(Masking.iban("NO00")).isEqualTo("NO00");
    }

    @Test
    void fodselsnummerLastFourVisible() {
        assertThat(Masking.fodselsnummer("12345678901")).isEqualTo("•••••••8901");
    }

    @Test
    void addressKeepsAfterComma() {
        // "Storgata 1" is 10 chars; comma at index 10.
        assertThat(Masking.address("Storgata 1, 0150 Oslo"))
                .isEqualTo("••••••••••, 0150 Oslo");
    }
}
