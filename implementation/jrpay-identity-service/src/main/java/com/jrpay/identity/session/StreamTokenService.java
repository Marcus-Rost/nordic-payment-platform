package com.jrpay.identity.session;

import com.jrpay.identity.domain.IdentityUser;
import com.jrpay.identity.domain.StreamToken;
import com.jrpay.identity.repository.StreamTokenRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Issues + consumes single-use SSE stream tokens per
 * {@code SSE_CONVENTIONS.md} §"Authenticated streams".
 *
 * <p>Tokens are 256-bit random URL-safe strings. We persist {@code SHA-256}
 * of the token (not the token itself) keyed by user + audience + purpose +
 * expiry. {@link #consume} returns the row + marks it consumed atomically;
 * a second consume returns empty.
 */
@Service
public class StreamTokenService {

    private static final Duration DEFAULT_TTL = Duration.ofMinutes(5);

    private final StreamTokenRepository repo;
    private final SecureRandom random = new SecureRandom();

    public StreamTokenService(StreamTokenRepository repo) {
        this.repo = repo;
    }

    @Transactional
    public Issued issue(UUID userId, IdentityUser.Audience audience, String purpose) {
        byte[] raw = new byte[32];
        random.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        OffsetDateTime expiresAt = OffsetDateTime.now().plus(DEFAULT_TTL);
        repo.save(new StreamToken(sha256Hex(token), userId, audience, purpose, expiresAt));
        return new Issued(token, expiresAt, DEFAULT_TTL.getSeconds());
    }

    @Transactional
    public Optional<StreamToken> consume(String token, String purpose) {
        Optional<StreamToken> opt = repo.findById(sha256Hex(token));
        if (opt.isEmpty()) return Optional.empty();
        StreamToken t = opt.get();
        if (!t.isLive() || !purpose.equals(t.getPurpose())) return Optional.empty();
        t.markConsumed();
        repo.save(t);
        return Optional.of(t);
    }

    public record Issued(String token, OffsetDateTime expiresAt, long expiresInSeconds) { }

    private static String sha256Hex(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 missing", e);
        }
    }
}
