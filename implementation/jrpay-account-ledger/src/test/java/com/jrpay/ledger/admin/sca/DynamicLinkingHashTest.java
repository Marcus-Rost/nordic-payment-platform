package com.jrpay.ledger.admin.sca;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class DynamicLinkingHashTest {

    private final DynamicLinkingHash subject = new DynamicLinkingHash();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void hashIsLowercaseHex64Chars() throws Exception {
        var inputs = mapper.readTree("""
                {"class":"admin-action","action":"FREEZE_ACCOUNT","resourceType":"account",
                 "resourceId":"ACC-001","notesHash":"deadbeef"}""");
        var result = subject.compute(inputs);
        assertThat(result.hex()).matches("^[0-9a-f]{64}$");
    }

    @Test
    void canonicalOutputSortsKeysLexicographically() throws Exception {
        // Members declared out of lex order should serialise the same as in lex order.
        var unordered = mapper.readTree("""
                {"resourceType":"account","class":"admin-action","action":"REVEAL_PII",
                 "resourceId":"ACC-001","notesHash":"abc","decision":"fodselsnummer"}""");
        var ordered = mapper.readTree("""
                {"action":"REVEAL_PII","class":"admin-action","decision":"fodselsnummer",
                 "notesHash":"abc","resourceId":"ACC-001","resourceType":"account"}""");
        assertThat(subject.compute(unordered).hex()).isEqualTo(subject.compute(ordered).hex());
    }

    @Test
    void differentNotesHashYieldsDifferentDigest() {
        var a = subject.computeAdminAction("FREEZE_ACCOUNT", "account", "ACC-001",
                null, DynamicLinkingHash.sha256HexNfc("note-a"));
        var b = subject.computeAdminAction("FREEZE_ACCOUNT", "account", "ACC-001",
                null, DynamicLinkingHash.sha256HexNfc("note-b"));
        assertThat(a.hex()).isNotEqualTo(b.hex());
    }

    @Test
    void differentActionYieldsDifferentDigest() {
        String notesHash = DynamicLinkingHash.sha256HexNfc("notes");
        var freeze = subject.computeAdminAction("FREEZE_ACCOUNT", "account", "ACC-001",
                null, notesHash);
        var unfreeze = subject.computeAdminAction("UNFREEZE_ACCOUNT", "account", "ACC-001",
                null, notesHash);
        assertThat(freeze.hex()).isNotEqualTo(unfreeze.hex());
    }

    @Test
    void decisionFieldOmittedWhenNull() {
        String notesHash = DynamicLinkingHash.sha256HexNfc("notes");
        var without = subject.computeAdminAction("FREEZE_ACCOUNT", "account", "ACC-001",
                null, notesHash);
        var withDecision = subject.computeAdminAction("FREEZE_ACCOUNT", "account", "ACC-001",
                "decision-value", notesHash);
        assertThat(without.canonical()).doesNotContain("decision");
        assertThat(without.hex()).isNotEqualTo(withDecision.hex());
    }

    @Test
    void nfcNormalisationCanonicalisesEquivalentForms() {
        // composed NFC form (single 0x00E9) vs decomposed (e + combining acute)
        String composed = "Clément";
        String decomposed = "Clément";
        String hashComposed = DynamicLinkingHash.sha256HexNfc(composed);
        String hashDecomposed = DynamicLinkingHash.sha256HexNfc(decomposed);
        assertThat(hashComposed).isEqualTo(hashDecomposed);
    }

    @Test
    void sha256HexNfcMatchesKnownVector() {
        // SHA-256("hello") = 2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824
        assertThat(DynamicLinkingHash.sha256HexNfc("hello"))
                .isEqualTo("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824");
    }
}
