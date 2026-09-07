package com.jrpay.identity.sca;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.Iterator;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Component;

/**
 * Computes the {@code dl_hash} per
 * {@code gui/architecture/DYNAMIC_LINKING.md} — RFC 8785 JSON canonicalisation
 * + UTF-8 NFC normalisation + SHA-256 + lowercase hex.
 *
 * <p>This implementation is deliberately small: the spec defines a finite set
 * of input shapes and we only need byte-identical output across server +
 * GUI platforms. A full RFC 8785 library would be heavy; the subset we use
 * is:
 * <ul>
 *   <li>members sorted lexicographically by UTF-16 code units (here we use
 *       Java String natural ordering, which is UTF-16),
 *   <li>strings UTF-8 NFC,
 *   <li>no whitespace,
 *   <li>arrays preserve order,
 *   <li>numbers omitted (the canonical input set declares all amounts as
 *       strings, so RFC 8785's number rules never apply).
 * </ul>
 *
 * <p>The matching test fixtures in
 * {@code src/test/resources/dl-hash-fixtures/} pin the byte output for each
 * canonical class.
 */
@Component
public class DynamicLinkingHash {

    private final ObjectMapper mapper;

    public DynamicLinkingHash() {
        this.mapper = new ObjectMapper();
    }

    public Computation compute(JsonNode inputs) {
        StringBuilder out = new StringBuilder();
        write(inputs, out);
        String canonical = out.toString();
        String hex = sha256Hex(canonical.getBytes(StandardCharsets.UTF_8));
        return new Computation(canonical, hex);
    }

    public Computation compute(String json) {
        try {
            return compute(mapper.readTree(json));
        } catch (Exception e) {
            throw new IllegalArgumentException("Inputs is not valid JSON: " + e.getMessage(), e);
        }
    }

    private void write(JsonNode node, StringBuilder out) {
        if (node == null || node.isNull()) {
            out.append("null");
            return;
        }
        if (node.isObject()) {
            // Lexicographic ordering of member names per RFC 8785.
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
            // Spec rule 2: amounts are strings — numbers should never appear
            // in canonical inputs. If one slips through, render via toString
            // (best-effort; will diverge across platforms — caller error).
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

    private static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(bytes);
            StringBuilder hex = new StringBuilder(64);
            for (byte b : digest) {
                hex.append(String.format("%02x", b & 0xff));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    public record Computation(String canonical, String hex) { }
}
