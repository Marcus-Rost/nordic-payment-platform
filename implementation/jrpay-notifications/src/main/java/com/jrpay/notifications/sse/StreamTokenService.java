package com.jrpay.notifications.sse;

import com.jrpay.notifications.domain.Audience;
import com.jrpay.notifications.domain.StreamTokenEntity;
import com.jrpay.notifications.repository.StreamTokenEntityRepository;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StreamTokenService {

    private final StreamTokenEntityRepository repo;
    private final Duration ttl;
    private final SecureRandom random = new SecureRandom();

    public StreamTokenService(StreamTokenEntityRepository repo,
                              @Value("${jrpay.notifications.sse.stream-token-ttl:PT5M}") Duration ttl) {
        this.repo = repo;
        this.ttl = ttl;
    }

    @Transactional
    public Issued issue(UUID userId, Audience audience, String purpose) {
        byte[] raw = new byte[32];
        random.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        OffsetDateTime expiresAt = OffsetDateTime.now().plus(ttl);
        repo.save(new StreamTokenEntity(sha256Hex(token), userId, audience, purpose, expiresAt));
        return new Issued(token, ttl.getSeconds());
    }

    @Transactional
    public Optional<StreamTokenEntity> consume(String token, String purpose) {
        Optional<StreamTokenEntity> opt = repo.findById(sha256Hex(token));
        if (opt.isEmpty()) return Optional.empty();
        StreamTokenEntity t = opt.get();
        if (!t.isLive() || !purpose.equals(t.getPurpose())) return Optional.empty();
        t.markConsumed();
        repo.save(t);
        return Optional.of(t);
    }

    public record Issued(String token, long expiresInSeconds) { }

    private static String sha256Hex(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
