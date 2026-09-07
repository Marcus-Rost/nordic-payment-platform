package com.jrpay.identity.passwordreset;

import com.jrpay.identity.domain.IdentityUser;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Default {@link EmailGateway} for the portfolio: logs the dispatch with the
 * token-hash + a redacted recipient. Test harnesses scrape the log to read
 * back the token-hash; the actual token is also logged at TRACE so a single
 * developer who flips the level can complete the round-trip locally.
 *
 * <p>Tests replace this with a {@code @Primary} bean.
 */
@Component
public class LoggingEmailGateway implements EmailGateway {

    private static final Logger log = LoggerFactory.getLogger(LoggingEmailGateway.class);
    private static final org.slf4j.Marker TOKEN = org.slf4j.MarkerFactory.getMarker("PASSWORD_RESET_TOKEN");

    @Override
    public void dispatchPasswordResetLink(String emailOrUsername,
                                          IdentityUser.Audience audience,
                                          String oneTimeToken,
                                          OffsetDateTime expiresAt) {
        log.info("password-reset email queued recipient_hash={} audience={} token_hash={} expires_at={}",
                sha256Hex(emailOrUsername), audience, sha256Hex(oneTimeToken), expiresAt);
        log.trace(TOKEN, "password-reset email token={} (TRACE-only; never log at INFO+)",
                oneTimeToken);
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 missing", e);
        }
    }
}
