package com.jrpay.audit.export;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SignedUrlServiceTest {

    private final ExportConfig config = new ExportConfig(
            100_000,
            Duration.ofHours(1),
            "test-only-signing-key-do-not-use-in-prod-32!!",
            "http://localhost/test/audit/export-bin");
    private final SignedUrlService signer = new SignedUrlService(config);

    @Test
    void buildsAndVerifiesUrl() {
        UUID requestId = UUID.randomUUID();
        OffsetDateTime expiresAt = OffsetDateTime.now().plusHours(1);
        String url = signer.buildDownloadUrl(requestId, "n0nce", expiresAt);

        assertThat(url).startsWith(config.signedBaseUrl())
                .contains("request_id=" + requestId)
                .contains("nonce=n0nce")
                .contains("exp=")
                .contains("sig=");

        String sig = url.replaceAll(".*sig=([^&]+).*", "$1");
        assertThat(signer.verify(requestId, "n0nce", expiresAt.toEpochSecond(), sig)).isTrue();
    }

    @Test
    void rejectsTamperedNonce() {
        UUID requestId = UUID.randomUUID();
        long exp = OffsetDateTime.now().plusHours(1).toEpochSecond();
        String url = signer.buildDownloadUrl(requestId, "good", OffsetDateTime.now().plusHours(1));
        String sig = url.replaceAll(".*sig=([^&]+).*", "$1");

        assertThat(signer.verify(requestId, "evil", exp, sig)).isFalse();
    }

    @Test
    void rejectsTamperedExpiry() {
        UUID requestId = UUID.randomUUID();
        OffsetDateTime expiresAt = OffsetDateTime.now().plusHours(1);
        String url = signer.buildDownloadUrl(requestId, "n", expiresAt);
        String sig = url.replaceAll(".*sig=([^&]+).*", "$1");

        // Signature was computed for the original epoch — bumping by 60s should fail.
        assertThat(signer.verify(requestId, "n", expiresAt.toEpochSecond() + 60, sig)).isFalse();
    }

    @Test
    void rejectsEmptySignature() {
        assertThat(signer.verify(UUID.randomUUID(), "n", System.currentTimeMillis() / 1000, ""))
                .isFalse();
        assertThat(signer.verify(UUID.randomUUID(), "n", System.currentTimeMillis() / 1000, null))
                .isFalse();
    }
}
