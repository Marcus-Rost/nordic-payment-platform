package com.jrpay.ledger.admin.sca;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Component;

/**
 * Computes the {@code dl_hash} per
 * {@code gui/architecture/DYNAMIC_LINKING.md}. Byte-identical to the
 * implementation in {@code jrpay-identity-service}; both must produce the
 * same hex output for the same logical input or the resource server will
 * reject legitimate operations.
 *
 * <p>The admin-action class hash is computed from a fixed-shape input:
 * {@code {class, action, resourceType, resourceId, decision?, notesHash}}.
 * The decision field is omitted from the JSON entirely when null (RFC 8785
 * does not allow optional explicit-null members in object keys for the
 * canonical input — they must be absent or present, not present-null).
 */
@Component
public class DynamicLinkingHash {

    private final ObjectMapper mapper = new ObjectMapper();

    public Computation compute(JsonNode inputs) {
        StringBuilder out = new StringBuilder();
        write(inputs, out);
        String canonical = out.toString();
        String hex = sha256Hex(canonical.getBytes(StandardCharsets.UTF_8));
        return new Computation(canonical, hex);
    }

    public Computation computeAdminAction(String action, String resourceType, String resourceId,
                                          String decision, String notesHash) {
        Map<String, Object> ordered = new LinkedHashMap<>();
        ordered.put("class", "admin-action");
        ordered.put("action", action);
        ordered.put("resourceType", resourceType);
        ordered.put("resourceId", resourceId);
        if (decision != null) {
            ordered.put("decision", decision);
        }
        ordered.put("notesHash", notesHash);
        return compute(mapper.valueToTree(ordered));
    }

    public static String sha256Hex(byte[] bytes) {
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

    public static String sha256HexNfc(String value) {
        String nfc = Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFC);
        return sha256Hex(nfc.getBytes(StandardCharsets.UTF_8));
    }

    private void write(JsonNode node, StringBuilder out) {
        if (node == null || node.isNull()) { out.append("null"); return; }
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
        if (node.isTextual()) { writeString(node.textValue(), out); return; }
        if (node.isBoolean()) { out.append(node.booleanValue() ? "true" : "false"); return; }
        if (node.isNumber()) { out.append(node.asText()); return; }
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

    public record Computation(String canonical, String hex) { }
}
