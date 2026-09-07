package com.jrpay.audit.export;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * Builds and verifies the URLs returned for ready audit-export downloads.
 * The URL embeds the request id, expiry timestamp, single-use nonce, and an
 * HMAC-SHA256 signature over those three fields. The download endpoint
 * re-derives the signature from the URL params and the configured key, and
 * compares constant-time.
 */
@Component
public class SignedUrlService {

    private static final String HMAC_ALG = "HmacSHA256";

    private final ExportConfig config;

    public SignedUrlService(ExportConfig config) {
        this.config = config;
    }

    public String buildDownloadUrl(UUID requestId, String nonce, OffsetDateTime expiresAt) {
        String exp = String.valueOf(expiresAt.toEpochSecond());
        String sig = sign(requestId.toString(), nonce, exp);
        return config.signedBaseUrl()
                + "?request_id=" + requestId
                + "&nonce=" + nonce
                + "&exp=" + exp
                + "&sig=" + sig;
    }

    public boolean verify(UUID requestId, String nonce, long exp, String providedSig) {
        if (providedSig == null || providedSig.isBlank()) return false;
        String expected = sign(requestId.toString(), nonce, String.valueOf(exp));
        return constantTimeEquals(expected, providedSig);
    }

    private String sign(String... fields) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALG);
            mac.init(new SecretKeySpec(config.signingKey().getBytes(StandardCharsets.UTF_8), HMAC_ALG));
            String payload = String.join("|", fields);
            byte[] raw = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC signing failed", e);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        byte[] av = a.getBytes(StandardCharsets.UTF_8);
        byte[] bv = b.getBytes(StandardCharsets.UTF_8);
        if (av.length != bv.length) return false;
        int diff = 0;
        for (int i = 0; i < av.length; i++) diff |= av[i] ^ bv[i];
        return diff == 0;
    }
}
