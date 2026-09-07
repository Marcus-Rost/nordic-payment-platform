package com.jrpay.audit.export;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jrpay.audit.chain.CanonicalJson;
import com.jrpay.audit.domain.AuditEvent;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Renders a JSON export bundle whose top-level layout is deterministic:
 *
 * <pre>
 * {
 *   "manifest": { exportId, generatedAt, eventCount, fromChainOffset, toChainOffset, payloadSha256 },
 *   "events":   [ ...stable canonical event projections... ],
 *   "signature": { algorithm: "HMAC-SHA256-RFC8785", value: <base64url> }
 * }
 * </pre>
 *
 * <p>The signature is computed over the canonical JSON of {@code manifest}
 * followed by the canonical JSON of {@code events}, so a downstream verifier
 * can re-derive the value byte-for-byte without having to trust the
 * ordering of object members in the wire form.
 */
public final class SignedJsonExportWriter {

    private static final String HMAC_ALG = "HmacSHA256";

    private SignedJsonExportWriter() {
    }

    public static byte[] render(UUID exportId,
                                List<AuditEvent> events,
                                String signingKey,
                                ObjectMapper mapper) {
        try {
            CanonicalJson canonical = new CanonicalJson();
            List<Map<String, Object>> projected = events.stream()
                    .map(SignedJsonExportWriter::project)
                    .toList();

            Map<String, Object> manifest = new LinkedHashMap<>();
            manifest.put("exportId", exportId.toString());
            manifest.put("generatedAt", OffsetDateTime.now().toString());
            manifest.put("eventCount", events.size());
            manifest.put("fromChainOffset", events.isEmpty() ? null : events.get(0).getChainOffset());
            manifest.put("toChainOffset", events.isEmpty() ? null : events.get(events.size() - 1).getChainOffset());
            manifest.put("payloadSha256", sha256Hex(canonicalArray(canonical, projected)));

            byte[] manifestBytes = canonical.encode(manifest);
            byte[] eventsBytes = canonicalArray(canonical, projected);
            byte[] signaturePayload = concat(manifestBytes, eventsBytes);

            String signatureValue = hmacBase64(signingKey, signaturePayload);

            Map<String, Object> bundle = new LinkedHashMap<>();
            bundle.put("manifest", manifest);
            bundle.put("events", projected);
            bundle.put("signature", Map.of(
                    "algorithm", "HMAC-SHA256-RFC8785",
                    "value", signatureValue));

            return mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(bundle);
        } catch (Exception e) {
            throw new IllegalStateException("Signed JSON export failed", e);
        }
    }

    private static Map<String, Object> project(AuditEvent e) {
        Map<String, Object> row = new TreeMap<>();
        row.put("chainOffset", e.getChainOffset());
        row.put("eventId", e.getEventId());
        row.put("occurredAt", e.getOccurredAt() == null ? null : e.getOccurredAt().toString());
        row.put("type", e.getType());
        row.put("actor", e.getActor());
        row.put("actorKind", e.getActorKind() == null ? null : e.getActorKind().name());
        row.put("outcome", e.getOutcome() == null ? null : e.getOutcome().name());
        row.put("severity", e.getSeverity() == null ? null : e.getSeverity().name());
        row.put("summary", e.getSummary());
        row.put("resourceType", e.getResourceType());
        row.put("resourceId", e.getResourceId());
        row.put("correlationId", asString(e.getCorrelationId()));
        row.put("customerUserId", asString(e.getCustomerUserId()));
        row.put("operatorUserId", asString(e.getOperatorUserId()));
        row.put("consentId", asString(e.getConsentId()));
        row.put("paymentId", asString(e.getPaymentId()));
        row.put("caseId", asString(e.getCaseId()));
        row.put("payload", e.getPayload());
        row.put("priorHash", e.getPriorHash());
        row.put("chainHash", e.getChainHash());
        return row;
    }

    private static String asString(UUID id) {
        return id == null ? null : id.toString();
    }

    private static byte[] canonicalArray(CanonicalJson canonical, List<Map<String, Object>> rows) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < rows.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(new String(canonical.encode(rows.get(i)), StandardCharsets.UTF_8));
        }
        sb.append(']');
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static String sha256Hex(byte[] bytes) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] digest = md.digest(bytes);
        StringBuilder hex = new StringBuilder(64);
        for (byte b : digest) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    private static String hmacBase64(String key, byte[] payload) throws Exception {
        Mac mac = Mac.getInstance(HMAC_ALG);
        mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), HMAC_ALG));
        byte[] raw = mac.doFinal(payload);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }
}
