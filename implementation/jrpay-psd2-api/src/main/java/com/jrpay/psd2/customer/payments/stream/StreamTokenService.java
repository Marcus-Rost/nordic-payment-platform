package com.jrpay.psd2.customer.payments.stream;

import com.jrpay.psd2.customer.CustomerProblemException;
import com.jrpay.psd2.customer.config.AuthenticatedUser;
import com.jrpay.psd2.customer.domain.StreamTokenEntity;
import com.jrpay.psd2.customer.domain.StreamTokenRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Mints + validates single-use SSE tokens. Persisted as
 * {@code SHA-256(token)} so the wire token never lands on disk.
 */
@Service
public class StreamTokenService {

    private final StreamTokenRepository tokens;
    private final AuthenticatedUser caller;
    private final Duration ttl;
    private final SecureRandom random = new SecureRandom();

    public StreamTokenService(StreamTokenRepository tokens,
                              AuthenticatedUser caller,
                              @Value("${jrpay.psd2.stream.ttl:PT5M}") Duration ttl) {
        this.tokens = tokens;
        this.caller = caller;
        this.ttl = ttl;
    }

    public record Issued(String token, long expiresInSeconds) {}

    @Transactional
    public Issued mint(String paymentIdScope) {
        UUID uid = caller.requireUserId();
        byte[] raw = new byte[32];
        random.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        Instant expiresAt = Instant.now().plus(ttl);
        tokens.save(new StreamTokenEntity(sha256Hex(token), uid, paymentIdScope, expiresAt));
        return new Issued(token, ttl.getSeconds());
    }

    @Transactional
    public Optional<UUID> consume(String token) {
        if (token == null || token.isBlank()) return Optional.empty();
        StreamTokenEntity entity = tokens.findByTokenHash(sha256Hex(token)).orElse(null);
        if (entity == null) return Optional.empty();
        if (!entity.isLive(Instant.now())) return Optional.empty();
        entity.markConsumed();
        tokens.save(entity);
        return Optional.of(entity.getCustomerUserId());
    }

    public Optional<UUID> peek(String token) {
        if (token == null || token.isBlank()) return Optional.empty();
        StreamTokenEntity entity = tokens.findByTokenHash(sha256Hex(token)).orElse(null);
        if (entity == null || !entity.isLive(Instant.now())) {
            return Optional.empty();
        }
        return Optional.of(entity.getCustomerUserId());
    }

    public Optional<String> peekScope(String token) {
        if (token == null || token.isBlank()) return Optional.empty();
        StreamTokenEntity entity = tokens.findByTokenHash(sha256Hex(token)).orElse(null);
        if (entity == null || !entity.isLive(Instant.now())) return Optional.empty();
        return Optional.ofNullable(entity.getPaymentId());
    }

    public static CustomerProblemException invalidToken() {
        return CustomerProblemException.stepUpRequired("Stream token invalid or expired");
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
