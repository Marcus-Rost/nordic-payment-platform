package com.jrpay.identity.sca;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jrpay.identity.IdentityIntegrationTestBase;
import com.jrpay.identity.domain.IdentityUser;
import com.jrpay.identity.domain.ScaFactor;
import com.jrpay.identity.domain.ScaSession;
import com.jrpay.identity.repository.IdentityUserRepository;
import com.jrpay.identity.repository.ScaFactorRepository;
import com.jrpay.identity.repository.ScaSessionRepository;
import com.jrpay.identity.repository.StepUpTokenRepository;
import dev.samstevens.totp.code.DefaultCodeGenerator;
import dev.samstevens.totp.time.SystemTimeProvider;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;

/**
 * Layer 2 — exercises the full SCA flow against a real Postgres + Spring
 * context.
 *
 * <p>Demo seed user {@code 550e8400-...440001} with TOTP factor secret
 * {@code JFCFAUKBKJEU2TQ=} (Base32) is used to compute valid 6-digit codes.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class ScaServiceIntegrationTest extends IdentityIntegrationTestBase {

    private static final UUID DEMO_CUSTOMER = UUID.fromString("550e8400-e29b-41d4-a716-446655440001");
    private static final String TOTP_SECRET = "JFCFAUKBKJEU2TQ=";

    @Autowired ScaService sca;
    @Autowired DynamicLinkingHash hashFn;
    @Autowired ScaSessionRepository sessions;
    @Autowired ScaFactorRepository factors;
    @Autowired StepUpTokenRepository stepUpTokens;
    @Autowired IdentityUserRepository users;
    @Autowired ObjectMapper mapper;

    @Test
    void startThenVerifyMintsStepUpTokenBoundToDlHash() throws Exception {
        var inputs = mapper.readTree(
                "{\"class\":\"payment\","
                        + "\"paymentId\":\"550e8400-e29b-41d4-a716-446655440000\","
                        + "\"amount\":\"100.00\","
                        + "\"currency\":\"NOK\","
                        + "\"creditorIban\":\"NO9386011117947\","
                        + "\"creditorName\":\"Anna Hansen\","
                        + "\"executionMode\":\"INSTANT\"}");
        var start = sca.start(DEMO_CUSTOMER, inputs, ScaFactor.FactorType.TOTP, false);
        assertThat(start.exempt()).isFalse();
        assertThat(start.dlHash()).matches("^[0-9a-f]{64}$");

        // Compute a valid TOTP code for the seeded secret.
        String code = new DefaultCodeGenerator()
                .generate(TOTP_SECRET, new SystemTimeProvider().getTime() / 30L);
        var verify = sca.verify(start.scaSessionId(), new ScaService.ScaProof.Totp(code));

        assertThat(verify.stepUpToken()).isNotEmpty();
        assertThat(sca.validateStepUpForOperation(verify.stepUpToken(), start.dlHash())).isTrue();
        // Token is single-use — second consume returns false.
        assertThat(sca.validateStepUpForOperation(verify.stepUpToken(), start.dlHash())).isFalse();
        // Mismatched dl_hash is rejected even when the token is fresh.
        var fresh = sca.start(DEMO_CUSTOMER, inputs, ScaFactor.FactorType.TOTP, false);
        var verifyFresh = sca.verify(fresh.scaSessionId(),
                new ScaService.ScaProof.Totp(new DefaultCodeGenerator()
                        .generate(TOTP_SECRET, new SystemTimeProvider().getTime() / 30L)));
        assertThat(sca.validateStepUpForOperation(verifyFresh.stepUpToken(), "deadbeef".repeat(8)))
                .isFalse();
    }

    @Test
    void verifyWithBadCodeIncrementsAttempts() throws Exception {
        var inputs = mapper.readTree(
                "{\"class\":\"payment\","
                        + "\"paymentId\":\"550e8400-e29b-41d4-a716-44665544aaaa\","
                        + "\"amount\":\"50.00\","
                        + "\"currency\":\"NOK\","
                        + "\"creditorIban\":\"NO9386011117947\","
                        + "\"creditorName\":\"Anna Hansen\","
                        + "\"executionMode\":\"INSTANT\"}");
        var start = sca.start(DEMO_CUSTOMER, inputs, ScaFactor.FactorType.TOTP, false);
        assertThatThrownBy(() ->
                sca.verify(start.scaSessionId(), new ScaService.ScaProof.Totp("000000")))
                .isInstanceOf(ScaService.ScaProofMismatchException.class);
        ScaSession reloaded = sessions.findById(start.scaSessionId()).orElseThrow();
        assertThat(reloaded.getAttempts()).isEqualTo(1);
        assertThat(reloaded.getState()).isEqualTo(ScaSession.State.PENDING);
    }

    @Test
    void exemptStartShortCircuitsToStepUpTokenImmediately() throws Exception {
        var inputs = mapper.readTree(
                "{\"class\":\"payment\","
                        + "\"paymentId\":\"550e8400-e29b-41d4-a716-44665544bbbb\","
                        + "\"amount\":\"10.00\","
                        + "\"currency\":\"NOK\","
                        + "\"creditorIban\":\"NO9386011117947\","
                        + "\"creditorName\":\"Anna Hansen\","
                        + "\"executionMode\":\"INSTANT\"}");
        var start = sca.start(DEMO_CUSTOMER, inputs, ScaFactor.FactorType.TOTP, true);
        assertThat(start.exempt()).isTrue();
        assertThat(start.stepUpToken()).isNotEmpty();
        assertThat(sca.validateStepUpForOperation(start.stepUpToken(), start.dlHash())).isTrue();
    }

    @Test
    void enrollmentReturnsBase32SecretAndPersistsFactor() {
        // Fresh user without a factor.
        IdentityUser fresh = users.save(new IdentityUser(
                UUID.randomUUID(),
                IdentityUser.Audience.customer,
                "no-factor@example.com",
                "no-factor@example.com",
                "$2a$10$qV3.HCZ/7l72KSlPjLktKuy9G3mNmoF/iXFkU8YWX1zh3lF5bKsny",
                IdentityUser.UserStatus.ACTIVE, null));
        var enroll = sca.enroll(fresh.getUserId(), ScaFactor.FactorType.TOTP);
        assertThat(enroll.setupSecret()).isNotEmpty();
        assertThat(factors.findByUserIdAndActiveTrue(fresh.getUserId()))
                .extracting(ScaFactor::getFactorType)
                .containsExactly(ScaFactor.FactorType.TOTP);
    }
}
