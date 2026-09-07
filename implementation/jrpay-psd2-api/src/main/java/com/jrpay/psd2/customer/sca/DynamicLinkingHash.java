package com.jrpay.psd2.customer.sca;

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
 * implementations in {@code jrpay-identity-service},
 * {@code jrpay-account-ledger}, and {@code jrpay-bill-payment}.
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

    public Computation compute(Map<String, Object> inputs) {
        JsonNode node = mapper.valueToTree(inputs);
        return compute(node);
    }

    public Map<String, Object> paymentInputs(String paymentId, String amount, String currency,
                                             String creditorIban, String creditorName,
                                             String executionMode) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("class", "payment");
        map.put("paymentId", paymentId);
        map.put("amount", amount);
        map.put("currency", currency);
        map.put("creditorIban", creditorIban);
        map.put("creditorName", creditorName);
        map.put("executionMode", executionMode);
        return map;
    }

    public Map<String, Object> paymentScheduleInputs(String scheduleId, String amount, String currency,
                                                     String creditorIban, String creditorName,
                                                     String executeAt, String executionMode) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("class", "payment-schedule");
        map.put("scheduleId", scheduleId);
        map.put("amount", amount);
        map.put("currency", currency);
        map.put("creditorIban", creditorIban);
        map.put("creditorName", creditorName);
        map.put("executeAt", executeAt);
        map.put("executionMode", executionMode);
        return map;
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

    public static String sha256HexUtf8(String value) {
        return sha256Hex((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
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
