package com.jrpay.billpay.sca;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DynamicLinkingHashTest {

    private final DynamicLinkingHash subject = new DynamicLinkingHash();

    @Test
    void paymentClassHashIsLowercaseHex64() {
        var inputs = subject.paymentInputs("p-1", "1234.56", "NOK",
                "NO9386011117947", "Hafslund Strøm AS", "INSTANT");
        assertThat(subject.compute(inputs).hex()).matches("^[0-9a-f]{64}$");
    }

    @Test
    void mandateClassDifferentFromBeneficiaryClass() {
        var mandate = subject.mandateInputs("m-1", "merchant-1", "1000.00", "NOK", "ACC-1");
        var beneficiary = subject.beneficiaryInputs("be-1", "Mormor", "NO9386011117947");
        assertThat(subject.compute(mandate).hex())
                .isNotEqualTo(subject.compute(beneficiary).hex());
    }

    @Test
    void nfcNormalisationCanonicalisesEquivalentInputs() {
        var composed = subject.beneficiaryInputs("be-1", "Clément", "NO9386011117947");
        var decomposed = subject.beneficiaryInputs("be-1", "Clément", "NO9386011117947");
        assertThat(subject.compute(composed).hex()).isEqualTo(subject.compute(decomposed).hex());
    }

    @Test
    void changingAmountChangesHash() {
        var a = subject.paymentInputs("p-1", "100.00", "NOK", "NO9386011117947", "x", "INSTANT");
        var b = subject.paymentInputs("p-1", "100.01", "NOK", "NO9386011117947", "x", "INSTANT");
        assertThat(subject.compute(a).hex()).isNotEqualTo(subject.compute(b).hex());
    }

    @Test
    void canonicalIsIndependentOfMapInsertionOrder() {
        var first = subject.compute(java.util.Map.of(
                "class", "payment",
                "paymentId", "p-1",
                "amount", "1.00",
                "currency", "NOK",
                "creditorIban", "NO9386011117947",
                "creditorName", "x",
                "executionMode", "INSTANT"));
        var second = subject.compute(java.util.Map.of(
                "executionMode", "INSTANT",
                "creditorIban", "NO9386011117947",
                "creditorName", "x",
                "currency", "NOK",
                "amount", "1.00",
                "paymentId", "p-1",
                "class", "payment"));
        assertThat(first.hex()).isEqualTo(second.hex());
    }
}
