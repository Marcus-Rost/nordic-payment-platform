package com.jrpay.audit.chain;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CanonicalJsonTest {

    private final CanonicalJson canonical = new CanonicalJson();

    @Test
    void sortsObjectMembersLexicographically() {
        Map<String, Object> in = new LinkedHashMap<>();
        in.put("z", 1);
        in.put("a", 2);
        in.put("m", 3);
        String out = new String(canonical.encode(in), StandardCharsets.UTF_8);
        assertThat(out).isEqualTo("{\"a\":2,\"m\":3,\"z\":1}");
    }

    @Test
    void escapesControlCharacters() {
        String out = new String(
                canonical.encode(Map.of("k", "line1\nline2\twith \"quotes\"")),
                StandardCharsets.UTF_8);
        assertThat(out).isEqualTo("{\"k\":\"line1\\nline2\\twith \\\"quotes\\\"\"}");
    }

    @Test
    void normalisesUnicodeToNfc() {
        // U+00E9 (single composed char) vs U+0065 + U+0301 (decomposed)
        String composed = "café";
        String decomposed = "café";
        byte[] a = canonical.encode(Map.of("k", composed));
        byte[] b = canonical.encode(Map.of("k", decomposed));
        assertThat(new String(a, StandardCharsets.UTF_8))
                .isEqualTo(new String(b, StandardCharsets.UTF_8));
    }

    @Test
    void recursivelyOrdersNestedObjects() {
        Map<String, Object> outer = new LinkedHashMap<>();
        Map<String, Object> inner = new LinkedHashMap<>();
        inner.put("z", "last");
        inner.put("a", "first");
        outer.put("nested", inner);
        outer.put("first", 1);
        String out = new String(canonical.encode(outer), StandardCharsets.UTF_8);
        assertThat(out).isEqualTo("{\"first\":1,\"nested\":{\"a\":\"first\",\"z\":\"last\"}}");
    }
}
