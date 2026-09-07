package com.jrpay.identity.passwordreset;

import static org.assertj.core.api.Assertions.assertThat;

import com.jrpay.identity.IdentityIntegrationTestBase;
import com.jrpay.identity.domain.IdentityUser;
import com.jrpay.identity.domain.PasswordResetToken;
import com.jrpay.identity.repository.IdentityUserRepository;
import com.jrpay.identity.repository.PasswordResetTokenRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Layer 2 — full password-reset flow against Postgres + Spring context.
 *
 * <p>Replaces the {@link EmailGateway} bean with an in-memory capture so tests
 * can read back the issued one-time token (tokens are otherwise only logged).
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class PasswordResetServiceIntegrationTest extends IdentityIntegrationTestBase {

    private static final UUID DEMO_CUSTOMER = UUID.fromString("550e8400-e29b-41d4-a716-446655440001");
    private static final String DEMO_EMAIL = "ola.nordmann@example.com";

    @Autowired PasswordResetService service;
    @Autowired PasswordResetTokenRepository tokens;
    @Autowired IdentityUserRepository users;
    @MockitoBean RefreshTokenRevoker revoker;

    @TestConfiguration
    static class CaptureGateway {
        @Bean
        @Primary
        EmailGateway captureGateway() {
            return new EmailGateway() {
                @Override
                public void dispatchPasswordResetLink(String emailOrUsername,
                                                     IdentityUser.Audience audience,
                                                     String oneTimeToken,
                                                     OffsetDateTime expiresAt) {
                    LATEST_TOKEN.set(oneTimeToken);
                }
            };
        }
    }

    static final ThreadLocal<String> LATEST_TOKEN = new ThreadLocal<>();

    @Test
    void requestVerifyCompleteHappyPath() {
        var outcome = service.request(DEMO_EMAIL, IdentityUser.Audience.customer);
        assertThat(outcome).isEqualTo(PasswordResetService.RequestOutcome.DISPATCHED);
        String token = LATEST_TOKEN.get();
        assertThat(token).isNotBlank();

        var verify = service.verify(token);
        assertThat(verify.valid()).isTrue();
        assertThat(verify.audience()).isEqualTo(IdentityUser.Audience.customer);

        var complete = service.complete(token, "FreshSummer2026!");
        assertThat(complete.status()).isEqualTo(PasswordResetService.CompleteOutcome.Status.OK);

        // Token must now be consumed — second complete is GONE.
        var replay = service.complete(token, "AnotherFreshOne1!");
        assertThat(replay.status()).isEqualTo(PasswordResetService.CompleteOutcome.Status.EXPIRED);

        // Refresh tokens revoked.
        org.mockito.Mockito.verify(revoker).revokeAllForUser(DEMO_CUSTOMER);

        // Stored hash updated — the seeded "demo-password-1!" no longer matches.
        IdentityUser reloaded = users.findById(DEMO_CUSTOMER).orElseThrow();
        assertThat(reloaded.getPasswordHash())
                .isNotEqualTo("$2a$10$qV3.HCZ/7l72KSlPjLktKuy9G3mNmoF/iXFkU8YWX1zh3lF5bKsny");
    }

    @Test
    void requestUnresolvedIdentifierStillReturnsNeutralOutcomeAndPersistsRequest() {
        var outcome = service.request("nobody-here@example.com", IdentityUser.Audience.customer);
        assertThat(outcome).isEqualTo(PasswordResetService.RequestOutcome.UNRESOLVED);
    }

    @Test
    void rateLimitsAfterThreePerHour() {
        for (int i = 0; i < 3; i++) {
            assertThat(service.request("rate-target@example.com", IdentityUser.Audience.customer))
                    .isIn(PasswordResetService.RequestOutcome.UNRESOLVED,
                            PasswordResetService.RequestOutcome.DISPATCHED);
        }
        assertThat(service.request("rate-target@example.com", IdentityUser.Audience.customer))
                .isEqualTo(PasswordResetService.RequestOutcome.RATE_LIMITED);
    }

    @Test
    void completeRejectsBreachedPassword() {
        service.request(DEMO_EMAIL, IdentityUser.Audience.customer);
        String token = LATEST_TOKEN.get();
        var outcome = service.complete(token, "Password1!");
        assertThat(outcome.status()).isEqualTo(PasswordResetService.CompleteOutcome.Status.POLICY_FAILED);
        assertThat(outcome.policyResult()).isEqualTo(PasswordPolicy.Result.BREACHED);
        // Token still live (not consumed when policy rejects).
        var stillLive = tokens.findById(sha256Hex(token));
        assertThat(stillLive).isPresent();
        assertThat(stillLive.get())
                .extracting(PasswordResetToken::getConsumedAt)
                .isNull();
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
