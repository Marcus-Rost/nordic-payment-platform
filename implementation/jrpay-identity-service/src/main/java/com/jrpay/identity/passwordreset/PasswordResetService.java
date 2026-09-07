package com.jrpay.identity.passwordreset;

import com.jrpay.identity.domain.IdentityUser;
import com.jrpay.identity.domain.PasswordResetRequest;
import com.jrpay.identity.domain.PasswordResetToken;
import com.jrpay.identity.repository.IdentityUserRepository;
import com.jrpay.identity.repository.PasswordResetRequestRepository;
import com.jrpay.identity.repository.PasswordResetTokenRepository;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * UC-G44 password reset.
 *
 * <p>Three steps:
 * <ol>
 *   <li>{@link #request} — neutral 202; if the identifier resolves, mint a
 *       single-use token (30-min TTL) and queue an email. Rate-limited to
 *       3/hour/identifier-hash.
 *   <li>{@link #verify} — accept token, return audience + masked identifier.
 *   <li>{@link #complete} — accept token + new password (policy-checked +
 *       breached-list); revoke all refresh tokens for the user.
 * </ol>
 *
 * <p>Token bytes themselves never persist — only {@code SHA-256(token)} is
 * stored. The token only exists in transit (the email) and in the
 * verification call.
 */
@Service
public class PasswordResetService {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);

    private final IdentityUserRepository users;
    private final PasswordResetTokenRepository tokens;
    private final PasswordResetRequestRepository requests;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicy passwordPolicy;
    private final EmailGateway emailGateway;
    private final RefreshTokenRevoker refreshTokenRevoker;
    private final SecureRandom random = new SecureRandom();
    private final Duration tokenTtl;
    private final int rateLimitPerHour;

    public PasswordResetService(IdentityUserRepository users,
                                PasswordResetTokenRepository tokens,
                                PasswordResetRequestRepository requests,
                                PasswordEncoder passwordEncoder,
                                PasswordPolicy passwordPolicy,
                                EmailGateway emailGateway,
                                RefreshTokenRevoker refreshTokenRevoker,
                                @Value("${jrpay.identity.password-reset.token-ttl:PT30M}") Duration tokenTtl,
                                @Value("${jrpay.identity.password-reset.rate-limit-per-hour:3}") int rateLimitPerHour) {
        this.users = users;
        this.tokens = tokens;
        this.requests = requests;
        this.passwordEncoder = passwordEncoder;
        this.passwordPolicy = passwordPolicy;
        this.emailGateway = emailGateway;
        this.refreshTokenRevoker = refreshTokenRevoker;
        this.tokenTtl = tokenTtl;
        this.rateLimitPerHour = rateLimitPerHour;
    }

    /** Always returns gracefully — caller surfaces a neutral 202 either way. */
    @Transactional
    public RequestOutcome request(String identifier, IdentityUser.Audience audience) {
        String idHash = sha256Hex(identifier.toLowerCase());
        long recent = requests.countByIdentifierHashAndIssuedAtAfter(
                idHash, OffsetDateTime.now().minus(Duration.ofHours(1)));
        if (recent >= rateLimitPerHour) {
            requests.save(new PasswordResetRequest(UUID.randomUUID(), idHash, audience, false));
            log.info("password-reset rate-limited for identifier hash={}", idHash);
            return RequestOutcome.RATE_LIMITED;
        }
        Optional<IdentityUser> userOpt = resolve(identifier, audience);
        boolean resolved = userOpt.isPresent();
        requests.save(new PasswordResetRequest(UUID.randomUUID(), idHash, audience, resolved));
        if (!resolved) {
            log.info("password-reset request for unresolved identifier hash={}", idHash);
            return RequestOutcome.UNRESOLVED;
        }
        IdentityUser user = userOpt.get();
        String token = generateToken();
        String hash = sha256Hex(token);
        OffsetDateTime expiry = OffsetDateTime.now().plus(tokenTtl);
        tokens.save(new PasswordResetToken(hash, user.getUserId(), audience, expiry));
        emailGateway.dispatchPasswordResetLink(
                user.getEmail() == null ? user.getUsername() : user.getEmail(),
                audience, token, expiry);
        return RequestOutcome.DISPATCHED;
    }

    @Transactional(readOnly = true)
    public VerifyOutcome verify(String token) {
        Optional<PasswordResetToken> opt = tokens.findById(sha256Hex(token));
        if (opt.isEmpty()) return VerifyOutcome.expired();
        PasswordResetToken t = opt.get();
        if (!t.isLive()) return VerifyOutcome.expired();
        IdentityUser user = users.findById(t.getUserId()).orElse(null);
        if (user == null) return VerifyOutcome.expired();
        return VerifyOutcome.valid(t.getAudience(), maskIdentifier(user));
    }

    @Transactional
    public CompleteOutcome complete(String token, String newPassword) {
        PasswordPolicy.Result policyResult = passwordPolicy.evaluate(newPassword);
        if (policyResult != PasswordPolicy.Result.OK) {
            return CompleteOutcome.policyFailed(policyResult);
        }
        Optional<PasswordResetToken> opt = tokens.findById(sha256Hex(token));
        if (opt.isEmpty()) return CompleteOutcome.expired();
        PasswordResetToken t = opt.get();
        if (!t.isLive()) return CompleteOutcome.expired();
        IdentityUser user = users.findById(t.getUserId())
                .orElseThrow(() -> new IllegalStateException("Token references missing user"));

        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.resetFailedAttempts();
        user.setLockedUntil(null);
        if (user.getStatus() == IdentityUser.UserStatus.LOCKED) {
            user.setStatus(IdentityUser.UserStatus.ACTIVE);
        }
        users.save(user);

        t.markConsumed();
        tokens.save(t);

        refreshTokenRevoker.revokeAllForUser(user.getUserId());
        return CompleteOutcome.OK;
    }

    private Optional<IdentityUser> resolve(String identifier, IdentityUser.Audience audience) {
        String lower = identifier.toLowerCase();
        Optional<IdentityUser> byEmail = users.findByEmail(lower);
        if (byEmail.isPresent() && byEmail.get().getAudience() == audience) {
            return byEmail;
        }
        return users.findByAudienceAndUsername(audience, lower);
    }

    private String generateToken() {
        byte[] raw = new byte[32];
        random.nextBytes(raw);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String maskIdentifier(IdentityUser user) {
        String value = user.getEmail() != null ? user.getEmail() : user.getUsername();
        int at = value.indexOf('@');
        if (at <= 1) return "*****" + value.substring(Math.max(0, at));
        return value.charAt(0) + "****" + value.substring(at);
    }

    public enum RequestOutcome { DISPATCHED, RATE_LIMITED, UNRESOLVED }

    public record VerifyOutcome(boolean valid, IdentityUser.Audience audience, String identifierMasked) {
        public static VerifyOutcome valid(IdentityUser.Audience audience, String masked) {
            return new VerifyOutcome(true, audience, masked);
        }
        public static VerifyOutcome expired() {
            return new VerifyOutcome(false, null, null);
        }
    }

    public record CompleteOutcome(Status status, PasswordPolicy.Result policyResult) {
        public static final CompleteOutcome OK = new CompleteOutcome(Status.OK, null);
        public static CompleteOutcome expired() {
            return new CompleteOutcome(Status.EXPIRED, null);
        }
        public static CompleteOutcome policyFailed(PasswordPolicy.Result r) {
            return new CompleteOutcome(Status.POLICY_FAILED, r);
        }
        public enum Status { OK, EXPIRED, POLICY_FAILED }
    }
}
