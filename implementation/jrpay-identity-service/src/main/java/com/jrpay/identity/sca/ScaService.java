package com.jrpay.identity.sca;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jrpay.identity.domain.IdentityUser;
import com.jrpay.identity.domain.ScaFactor;
import com.jrpay.identity.domain.ScaSession;
import com.jrpay.identity.domain.StepUpToken;
import com.jrpay.identity.repository.IdentityUserRepository;
import com.jrpay.identity.repository.ScaFactorRepository;
import com.jrpay.identity.repository.ScaSessionRepository;
import com.jrpay.identity.repository.StepUpTokenRepository;
import dev.samstevens.totp.code.CodeVerifier;
import dev.samstevens.totp.code.DefaultCodeGenerator;
import dev.samstevens.totp.code.DefaultCodeVerifier;
import dev.samstevens.totp.secret.DefaultSecretGenerator;
import dev.samstevens.totp.secret.SecretGenerator;
import dev.samstevens.totp.time.SystemTimeProvider;
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

/**
 * Implements UC-G51 SCA orchestration:
 * <ul>
 *   <li>{@code start} — compute {@code dl_hash} over canonical inputs, persist
 *       a pending {@link ScaSession}, return challenge metadata. PSD2 RTS
 *       Art. 16 / 18 exemptions are recognised here at call sites that pass
 *       {@code exempt=true} after their own checks (e.g. the resource-server
 *       gate on a low-value payment); when exempt, a step-up token is minted
 *       immediately.
 *   <li>{@code verify} — verify the proof against the user's enrolled factor,
 *       transition the session to {@code VERIFIED}, mint a step-up token
 *       carrying {@code dl_hash} as a JWT-style claim (here implemented as a
 *       random opaque token keyed in {@code identity_step_up_tokens}).
 *   <li>{@code enroll} — provision a new factor (TOTP only in MVP).
 * </ul>
 */
@Service
public class ScaService {

    private final ScaSessionRepository sessions;
    private final ScaFactorRepository factors;
    private final StepUpTokenRepository stepUpTokens;
    private final IdentityUserRepository users;
    private final DynamicLinkingHash hashFn;
    private final ObjectMapper mapper = new ObjectMapper();
    private final CodeVerifier totpVerifier;
    private final SecretGenerator totpSecretGenerator = new DefaultSecretGenerator();
    private final SecureRandom random = new SecureRandom();

    private final int maxAttempts;
    private final Duration sessionTtl;
    private final Duration stepUpTtl;

    public ScaService(ScaSessionRepository sessions,
                      ScaFactorRepository factors,
                      StepUpTokenRepository stepUpTokens,
                      IdentityUserRepository users,
                      DynamicLinkingHash hashFn,
                      @Value("${jrpay.identity.sca.max-attempts:3}") int maxAttempts,
                      @Value("${jrpay.identity.sca.session-ttl:PT5M}") Duration sessionTtl,
                      @Value("${jrpay.identity.jwt.step-up-token-ttl:PT5M}") Duration stepUpTtl) {
        this.sessions = sessions;
        this.factors = factors;
        this.stepUpTokens = stepUpTokens;
        this.users = users;
        this.hashFn = hashFn;
        this.maxAttempts = maxAttempts;
        this.sessionTtl = sessionTtl;
        this.stepUpTtl = stepUpTtl;
        this.totpVerifier = new DefaultCodeVerifier(new DefaultCodeGenerator(), new SystemTimeProvider());
    }

    @Transactional
    public StartResult start(UUID userId, JsonNode inputs, ScaFactor.FactorType factorHint, boolean exempt) {
        if (inputs == null || !inputs.isObject() || !inputs.has("class") || !inputs.get("class").isTextual()) {
            throw new IllegalArgumentException("inputs must be a JSON object with a 'class' string field");
        }
        IdentityUser user = users.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown user: " + userId));
        if (user.isLockedNow()) {
            throw new ScaLockedException("User is locked");
        }

        DynamicLinkingHash.Computation hash = hashFn.compute(inputs);
        ScaFactor.FactorType factorType = chooseFactor(userId, factorHint);

        UUID sessionId = UUID.randomUUID();
        OffsetDateTime expiry = OffsetDateTime.now().plus(sessionTtl);
        ScaSession session = new ScaSession(
                sessionId, userId,
                inputs.get("class").asText(),
                hash.hex(), hash.canonical(),
                factorType, expiry, exempt);
        sessions.save(session);

        String stepUpToken = null;
        if (exempt) {
            stepUpToken = mintStepUpToken(userId, sessionId, hash.hex());
        }
        return new StartResult(sessionId, hash.hex(), factorType, exempt, stepUpToken);
    }

    @Transactional(noRollbackFor = {
            ScaProofMismatchException.class,
            ScaTimeoutException.class,
            ScaLockedException.class
    })
    public VerifyResult verify(UUID sessionId, ScaProof proof) {
        ScaSession session = sessions.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown SCA session"));
        if (session.getState() != ScaSession.State.PENDING) {
            throw new IllegalStateException("Session is not pending: " + session.getState());
        }
        if (session.isExpired()) {
            session.markExpired();
            sessions.save(session);
            throw new ScaTimeoutException("SCA session expired");
        }
        ScaFactor factor = factors.findFirstByUserIdAndFactorTypeAndActiveTrue(
                session.getUserId(), session.getFactorType())
                .orElseThrow(() -> new IllegalStateException(
                        "No active " + session.getFactorType() + " factor for user"));

        boolean ok = matchesProof(factor, proof);
        session.recordAttempt();
        if (!ok) {
            if (session.getAttempts() >= maxAttempts) {
                session.markFailed();
                sessions.save(session);
                IdentityUser user = users.getReferenceById(session.getUserId());
                user.setLockedUntil(OffsetDateTime.now().plus(Duration.ofMinutes(15)));
                user.setStatus(IdentityUser.UserStatus.LOCKED);
                users.save(user);
                throw new ScaLockedException("Too many failed SCA attempts");
            }
            sessions.save(session);
            throw new ScaProofMismatchException("Proof rejected");
        }

        session.markVerified();
        factor.markUsed();
        factors.save(factor);
        sessions.save(session);

        String stepUp = mintStepUpToken(session.getUserId(), sessionId, session.getDlHash());
        return new VerifyResult(stepUp, session.getDlHash(), stepUpTtl.getSeconds());
    }

    @Transactional
    public EnrollResult enroll(UUID userId, ScaFactor.FactorType factorType) {
        if (factorType != ScaFactor.FactorType.TOTP) {
            throw new IllegalArgumentException("MVP only supports TOTP enrolment; got " + factorType);
        }
        users.findById(userId).orElseThrow(() -> new IllegalArgumentException("Unknown user"));
        String secret = totpSecretGenerator.generate();
        ScaFactor factor = new ScaFactor(UUID.randomUUID(), userId, ScaFactor.FactorType.TOTP, secret);
        factors.save(factor);
        return new EnrollResult(factor.getFactorId(), secret);
    }

    @Transactional
    public boolean validateStepUpForOperation(String token, String expectedDlHash) {
        return validateStepUp(token, expectedDlHash).valid();
    }

    /**
     * Validates a step-up token presented by a resource server. Single-use: a
     * successful validation marks the token as consumed.
     *
     * <p>Outcomes:
     * <ul>
     *   <li>{@link Outcome#NOT_FOUND} — token not on file (forged, expired and
     *       garbage-collected, or never issued).
     *   <li>{@link Outcome#EXPIRED} — token is on file but past TTL or already
     *       consumed.
     *   <li>{@link Outcome#DL_HASH_MISMATCH} — token is live but bound to a
     *       different operation. The resource server should respond with
     *       {@code 403 urn:problem:dynamic-linking-mismatch}.
     *   <li>{@link Outcome#VALID} — token is live and bound to the expected
     *       operation; consumed.
     * </ul>
     */
    @Transactional
    public ValidationResult validateStepUp(String token, String expectedDlHash) {
        if (token == null || expectedDlHash == null) {
            return new ValidationResult(false, Outcome.NOT_FOUND, null, null, null);
        }
        String hash = sha256Hex(token);
        Optional<StepUpToken> opt = stepUpTokens.findById(hash);
        if (opt.isEmpty()) {
            return new ValidationResult(false, Outcome.NOT_FOUND, null, null, null);
        }
        StepUpToken sut = opt.get();
        if (!sut.isLive()) {
            return new ValidationResult(false, Outcome.EXPIRED, sut.getUserId(),
                    sut.getDlHash(), sut.getScaSessionId());
        }
        if (!expectedDlHash.equalsIgnoreCase(sut.getDlHash())) {
            return new ValidationResult(false, Outcome.DL_HASH_MISMATCH, sut.getUserId(),
                    sut.getDlHash(), sut.getScaSessionId());
        }
        sut.markConsumed();
        stepUpTokens.save(sut);
        return new ValidationResult(true, Outcome.VALID, sut.getUserId(),
                sut.getDlHash(), sut.getScaSessionId());
    }

    public enum Outcome { VALID, NOT_FOUND, EXPIRED, DL_HASH_MISMATCH }

    public record ValidationResult(boolean valid, Outcome outcome, UUID userId,
                                   String dlHash, UUID scaSessionId) { }

    private ScaFactor.FactorType chooseFactor(UUID userId, ScaFactor.FactorType hint) {
        if (hint != null) {
            return factors.findFirstByUserIdAndFactorTypeAndActiveTrue(userId, hint)
                    .map(ScaFactor::getFactorType)
                    .orElseThrow(() -> new IllegalStateException(
                            "Hinted factor " + hint + " is not enrolled"));
        }
        var enrolled = factors.findByUserIdAndActiveTrue(userId);
        if (enrolled.isEmpty()) {
            throw new IllegalStateException("User has no enrolled SCA factor");
        }
        return enrolled.get(0).getFactorType();
    }

    private boolean matchesProof(ScaFactor factor, ScaProof proof) {
        return switch (factor.getFactorType()) {
            case TOTP -> proof instanceof ScaProof.Totp totp
                    && totpVerifier.isValidCode(factor.getSecret(), totp.code());
            case BANKID_SIMULATED -> proof instanceof ScaProof.BankId bid
                    && factor.getSecret().equals(bid.assertion());
            case BANKID_REAL ->
                // Out of MVP scope — would call the BankID merchant API.
                false;
            case BIOMETRIC ->
                // Biometric is device-bound; the resource-server gates on
                // device-integrity headers + a separate signed assertion.
                false;
        };
    }

    private String mintStepUpToken(UUID userId, UUID sessionId, String dlHash) {
        byte[] raw = new byte[32];
        random.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        String hash = sha256Hex(token);
        OffsetDateTime expiry = OffsetDateTime.now().plus(stepUpTtl);
        stepUpTokens.save(new StepUpToken(hash, userId, sessionId, dlHash, expiry));
        return token;
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 missing", e);
        }
    }

    public record StartResult(UUID scaSessionId, String dlHash, ScaFactor.FactorType factorType,
                              boolean exempt, String stepUpToken) { }

    public record VerifyResult(String stepUpToken, String dlHash, long expiresInSeconds) { }

    public record EnrollResult(UUID enrollmentId, String setupSecret) { }

    public sealed interface ScaProof {
        record Totp(String code) implements ScaProof { }
        record BankId(String assertion) implements ScaProof { }
    }

    public static class ScaProofMismatchException extends RuntimeException {
        public ScaProofMismatchException(String msg) { super(msg); }
    }
    public static class ScaTimeoutException extends RuntimeException {
        public ScaTimeoutException(String msg) { super(msg); }
    }
    public static class ScaLockedException extends RuntimeException {
        public ScaLockedException(String msg) { super(msg); }
    }
}
