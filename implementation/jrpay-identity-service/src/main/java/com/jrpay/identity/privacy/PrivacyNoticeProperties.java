package com.jrpay.identity.privacy;

import java.time.OffsetDateTime;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configurable metadata for the currently-published privacy notice. Real
 * deployments source this from a CMS; for the portfolio default the notice
 * ships with the application.yml.
 */
@ConfigurationProperties(prefix = "jrpay.identity.privacy-notice")
public record PrivacyNoticeProperties(
        String currentVersion,
        OffsetDateTime publishedAt,
        boolean materialChange,
        String changeSummaryKey,
        String mdUrl,
        String pdfUrl
) {
    public PrivacyNoticeProperties {
        if (currentVersion == null) currentVersion = "v1";
        if (publishedAt == null) publishedAt = OffsetDateTime.parse("2026-04-24T00:00:00Z");
        if (mdUrl == null) mdUrl = "https://docs.jrpay.example/privacy/v1.md";
        if (pdfUrl == null) pdfUrl = "https://docs.jrpay.example/privacy/v1.pdf";
    }
}
