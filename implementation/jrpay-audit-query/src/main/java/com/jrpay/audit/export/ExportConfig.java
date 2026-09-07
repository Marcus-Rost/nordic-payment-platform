package com.jrpay.audit.export;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration block bound from {@code jrpay.audit.export.*}. Held in a
 * dedicated record so test slices can override it without booting the whole
 * application context.
 */
@ConfigurationProperties("jrpay.audit.export")
public record ExportConfig(
        int maxRows,
        Duration signedUrlTtl,
        String signingKey,
        String signedBaseUrl
) {
    public ExportConfig {
        if (maxRows <= 0) maxRows = 100_000;
        if (signedUrlTtl == null) signedUrlTtl = Duration.ofHours(1);
    }
}
