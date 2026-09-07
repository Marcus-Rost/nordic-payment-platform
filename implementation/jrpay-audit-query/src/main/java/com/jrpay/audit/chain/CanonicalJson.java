package com.jrpay.audit.chain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.text.Normalizer;
import java.util.Iterator;
import java.util.Map;
import java.util.TreeMap;

/**
 * Canonical-JSON serialiser used by the chain-hash. Deliberately the same
 * subset as {@code DynamicLinkingHash} in identity-service — RFC 8785 with
 * the constraints enumerated in {@code DYNAMIC_LINKING.md} (NFC strings,
 * lex-sorted member names, no whitespace). Numbers are represented as
 * Jackson's default text — chain inputs never carry monetary numbers
 * (amounts already arrive as strings).
 *
 * <p>Reusing the algorithm here means an audit consumer can
 * cross-verify a chain hash from outside the service without depending
 * on the JVM at all — pin the bytes against the spec.
 */
public final class CanonicalJson {

    private final ObjectMapper mapper;

    public CanonicalJson() {
        this.mapper = new ObjectMapper();
    }

    public byte[] encode(JsonNode node) {
        StringBuilder out = new StringBuilder();
        write(node, out);
        return out.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    public byte[] encode(Map<String, ?> map) {
        JsonNode tree = mapper.valueToTree(map);
        return encode(tree);
    }

    private void write(JsonNode node, StringBuilder out) {
        if (node == null || node.isNull()) {
            out.append("null");
            return;
        }
        if (node.isObject()) {
            Map<String, JsonNode> sorted = new TreeMap<>();
            Iterator<Map.Entry<String, JsonNode>> it = node.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                sorted.put(e.getKey(), e.getValue());
            }
            out.append('{');
            boolean first = true;
            for (var e : sorted.entrySet()) {
                if (!first) out.append(',');
                first = false;
                writeString(e.getKey(), out);
                out.append(':');
                write(e.getValue(), out);
            }
            out.append('}');
            return;
        }
        if (node.isArray()) {
            out.append('[');
            boolean first = true;
            for (JsonNode child : node) {
                if (!first) out.append(',');
                first = false;
                write(child, out);
            }
            out.append(']');
            return;
        }
        if (node.isTextual()) {
            writeString(node.textValue(), out);
            return;
        }
        if (node.isBoolean()) {
            out.append(node.booleanValue() ? "true" : "false");
            return;
        }
        if (node.isNumber()) {
            out.append(node.asText());
            return;
        }
        out.append("null");
    }

    private void writeString(String value, StringBuilder out) {
        String nfc = Normalizer.normalize(value, Normalizer.Form.NFC);
        out.append('"');
        for (int i = 0; i < nfc.length(); i++) {
            char c = nfc.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }
}
