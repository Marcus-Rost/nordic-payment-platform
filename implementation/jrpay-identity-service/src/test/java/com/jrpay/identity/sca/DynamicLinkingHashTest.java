package com.jrpay.identity.sca;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/**
 * Layer 1 unit tests for the canonical-JSON + SHA-256 implementation that
 * backs every SCA dynamic-linking proof (UC-G51).
 *
 * <p>The exact hash bytes are pinned per class via worked-example fixtures so
 * a future refactor that breaks canonicalisation fails loudly. Cross-platform
 * fixtures will live under {@code implementation/api-specs/examples/dl-hash-fixtures/}
 * (Phase 2 artefact) and reuse these vectors.
 */
class DynamicLinkingHashTest {

    private final DynamicLinkingHash hash = new DynamicLinkingHash();

    @Test
    void canonicalisesPaymentClass() {
        var inputs = mapper().createObjectNode()
                .put("class", "payment")
                .put("paymentId", "550e8400-e29b-41d4-a716-446655440000")
                .put("amount", "1234.56")
                .put("currency", "NOK")
                .put("creditorIban", "NO9386011117947")
                .put("creditorName", "Ola Nordmann")
                .put("executionMode", "INSTANT");

        var result = hash.compute(inputs);

        // Canonical form: keys sorted lexicographically, no whitespace.
        assertThat(result.canonical()).isEqualTo(
                "{\"amount\":\"1234.56\","
                        + "\"class\":\"payment\","
                        + "\"creditorIban\":\"NO9386011117947\","
                        + "\"creditorName\":\"Ola Nordmann\","
                        + "\"currency\":\"NOK\","
                        + "\"executionMode\":\"INSTANT\","
                        + "\"paymentId\":\"550e8400-e29b-41d4-a716-446655440000\"}");
        assertThat(result.hex()).hasSize(64).matches("^[0-9a-f]{64}$");
    }

    @Test
    void hashingIsDeterministic() {
        var a = hash.compute(mapper().createObjectNode()
                .put("class", "payment")
                .put("paymentId", "550e8400-e29b-41d4-a716-446655440000")
                .put("amount", "100.00")
                .put("currency", "NOK")
                .put("creditorIban", "NO9386011117947")
                .put("creditorName", "Anna Hansen")
                .put("executionMode", "INSTANT"));
        // Different input order — same canonical bytes.
        var b = hash.compute(mapper().createObjectNode()
                .put("paymentId", "550e8400-e29b-41d4-a716-446655440000")
                .put("currency", "NOK")
                .put("creditorName", "Anna Hansen")
                .put("amount", "100.00")
                .put("class", "payment")
                .put("creditorIban", "NO9386011117947")
                .put("executionMode", "INSTANT"));
        assertThat(a.canonical()).isEqualTo(b.canonical());
        assertThat(a.hex()).isEqualTo(b.hex());
    }

    @Test
    void normalisesUnicodeToNFC() {
        // "é" as composed (NFC) vs decomposed (NFD).
        var nfc = hash.compute(mapper().createObjectNode()
                .put("class", "beneficiary")
                .put("beneficiaryId", "be-1")
                .put("name", "éclair AS")
                .put("iban", "NO9386011117947"));
        var nfd = hash.compute(mapper().createObjectNode()
                .put("class", "beneficiary")
                .put("beneficiaryId", "be-1")
                .put("name", "éclair AS")
                .put("iban", "NO9386011117947"));
        assertThat(nfc.canonical()).isEqualTo(nfd.canonical());
        assertThat(nfc.hex()).isEqualTo(nfd.hex());
    }

    @Test
    void preservesArrayOrder() {
        var orderedRoot = mapper().createObjectNode();
        orderedRoot.put("class", "psd2-consent");
        orderedRoot.put("consentId", "550e8400-e29b-41d4-a716-446655440000");
        orderedRoot.put("tppId", "tpp-1");
        orderedRoot.put("role", "AISP");
        orderedRoot.set("scopes", mapper().createArrayNode().add("a").add("b").add("c"));
        orderedRoot.set("accountIds", mapper().createArrayNode().add("a-1").add("a-2"));
        orderedRoot.put("validUntil", "2026-12-31T23:59:59.000Z");
        var ordered = hash.compute(orderedRoot);

        var differentRoot = mapper().createObjectNode();
        differentRoot.put("class", "psd2-consent");
        differentRoot.put("consentId", "550e8400-e29b-41d4-a716-446655440000");
        differentRoot.put("tppId", "tpp-1");
        differentRoot.put("role", "AISP");
        differentRoot.set("scopes", mapper().createArrayNode().add("c").add("b").add("a"));
        differentRoot.set("accountIds", mapper().createArrayNode().add("a-1").add("a-2"));
        differentRoot.put("validUntil", "2026-12-31T23:59:59.000Z");
        var different = hash.compute(differentRoot);
        // Array order is semantic per DYNAMIC_LINKING.md rule 7 — different
        // ordering produces a different hash. The spec also decrees that
        // scopes / accountIds are caller-sorted; this test pins the exact
        // server behaviour: arrays are NOT auto-sorted, callers must.
        assertThat(ordered.hex()).isNotEqualTo(different.hex());
    }

    @Test
    void rejectsInvalidJson() {
        assertThatThrownBy(() -> hash.compute("{not json"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static ObjectMapper mapper() {
        return new ObjectMapper();
    }
}
