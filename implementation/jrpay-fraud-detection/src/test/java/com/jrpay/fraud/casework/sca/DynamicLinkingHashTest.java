package com.jrpay.fraud.casework.sca;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DynamicLinkingHashTest {

    private final DynamicLinkingHash subject = new DynamicLinkingHash();

    @Test
    void adminActionHashIsLowercaseHex64() {
        var result = subject.computeAdminAction("DECIDE_FRAUD", "case",
                "11111111-2222-3333-4444-555555555555", "CONFIRMED_FRAUD",
                DynamicLinkingHash.sha256HexNfc("notes"));
        assertThat(result.hex()).matches("^[0-9a-f]{64}$");
    }

    @Test
    void differentDecisionYieldsDifferentDigest() {
        String notesHash = DynamicLinkingHash.sha256HexNfc("notes");
        var confirm = subject.computeAdminAction("DECIDE_FRAUD", "case",
                "id-1", "CONFIRMED_FRAUD", notesHash);
        var dismiss = subject.computeAdminAction("DECIDE_FRAUD", "case",
                "id-1", "DISMISSED", notesHash);
        assertThat(confirm.hex()).isNotEqualTo(dismiss.hex());
    }

    @Test
    void nfcNormalisationCanonicalisesEquivalentForms() {
        String composed = DynamicLinkingHash.sha256HexNfc("Clément");
        String decomposed = DynamicLinkingHash.sha256HexNfc("Clément");
        assertThat(composed).isEqualTo(decomposed);
    }
}
