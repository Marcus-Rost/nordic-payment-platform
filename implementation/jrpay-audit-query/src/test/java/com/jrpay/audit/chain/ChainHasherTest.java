package com.jrpay.audit.chain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ChainHasherTest {

    private final ChainHasher hasher = new ChainHasher();

    @Test
    void firstEventUsesNullPriorHash() {
        String h = hasher.hash(Map.of("type", "x"), null, 1);
        assertThat(h).hasSize(64).matches("[0-9a-f]+");
    }

    @Test
    void differentPayloadsProduceDifferentHashes() {
        String a = hasher.hash(Map.of("type", "a"), null, 1);
        String b = hasher.hash(Map.of("type", "b"), null, 1);
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void payloadKeyOrderDoesNotAffectHash() {
        Map<String, Object> ordered = new LinkedHashMap<>();
        ordered.put("a", 1);
        ordered.put("b", 2);
        Map<String, Object> reversed = new LinkedHashMap<>();
        reversed.put("b", 2);
        reversed.put("a", 1);
        assertThat(hasher.hash(ordered, "abc", 5))
                .isEqualTo(hasher.hash(reversed, "abc", 5));
    }

    @Test
    void priorHashFlipChangesOutput() {
        Map<String, Object> payload = Map.of("type", "x");
        assertThat(hasher.hash(payload, "00", 7))
                .isNotEqualTo(hasher.hash(payload, "01", 7));
    }

    @Test
    void chainOffsetFlipChangesOutput() {
        Map<String, Object> payload = Map.of("type", "x");
        assertThat(hasher.hash(payload, "abc", 1))
                .isNotEqualTo(hasher.hash(payload, "abc", 2));
    }
}
