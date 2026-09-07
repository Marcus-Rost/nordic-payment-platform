package com.jrpay.audit.chain;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Computes the chain hash for a single audit event.
 *
 * <pre>
 *   chainHash = sha256(canonicalJson(payload) || priorHash || chainOffset)
 * </pre>
 *
 * Where {@code priorHash} is the empty string for the first row
 * (chain_offset = 1). Verifiers re-run this function with the stored
 * payload + the previous row's chainHash and compare; mismatch ⇒
 * tampering or storage corruption.
 */
@Component
public class ChainHasher {

    private final CanonicalJson canonical;
    private final ObjectMapper mapper = new ObjectMapper();

    public ChainHasher() {
        this.canonical = new CanonicalJson();
    }

    public String hash(Map<String, ?> payload, String priorHash, long chainOffset) {
        byte[] canonicalBytes = canonical.encode(payload);
        return hash(canonicalBytes, priorHash, chainOffset);
    }

    public String hash(byte[] canonicalBytes, String priorHash, long chainOffset) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(canonicalBytes);
            md.update((byte) '|');
            if (priorHash != null) {
                md.update(priorHash.getBytes(StandardCharsets.UTF_8));
            }
            md.update((byte) '|');
            md.update(Long.toString(chainOffset).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 missing", e);
        }
    }

    /**
     * Variant accepting raw JSON text — useful when the producer already
     * supplies a string payload (Kafka raw value).
     */
    public String hashJson(String payloadJson, String priorHash, long chainOffset) {
        try {
            return hash(canonical.encode(mapper.readTree(payloadJson)), priorHash, chainOffset);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid JSON payload: " + e.getMessage(), e);
        }
    }
}
