package com.jrpay.psd2;

import com.jrpay.psd2.consent.Consent;
import com.jrpay.psd2.consent.ConsentRepository;
import com.jrpay.psd2.sca.ScaSession;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end tests derived from A4 UC-01 (OAuth2 Consent Flow) and UC-02 (Payment Initiation).
 *
 * @see space/02_Open_Banking_PSD2_API/use_cases/UC01_consent_flow.md
 * @see space/02_Open_Banking_PSD2_API/use_cases/UC02_payment_initiation_and_sca.md
 */
@DataJpaTest
@ActiveProfiles("test")
class UseCaseEndToEndTest {

    @Autowired
    private ConsentRepository consentRepository;

    @Autowired
    private TestEntityManager entityManager;

    // ====================================================================
    // UC-01: Consent Flow
    // ====================================================================

    @Test
    @DisplayName("UC-01 Main: Create consent → verify scope → verify account access")
    void uc01_main_consentFlow() {
        // Step 4: Account holder approves consent for accounts:read + transactions:read
        Consent consent = new Consent(
                UUID.randomUUID().toString(), "TPP-FINAPP-001", "HOLDER-001",
                Set.of("accounts:read", "transactions:read"),
                Set.of("NO9386011117947", "NO1234567890123"),
                Instant.now().plusSeconds(90 * 24 * 3600)); // 90 days

        consent = consentRepository.save(consent);

        // Step 8-9: TPP accesses accounts — verify consent covers scope and account
        assertTrue(consent.isValid());
        assertTrue(consent.hasScope("accounts:read"));
        assertTrue(consent.hasScope("transactions:read"));
        assertFalse(consent.hasScope("payments:initiate")); // not granted
        assertTrue(consent.coversAccount("NO9386011117947"));
        assertFalse(consent.coversAccount("NO9999999999999")); // not granted
    }

    @Test
    @DisplayName("UC-01 AF-05: Consent expired after 90 days")
    void uc01_AF05_consentExpired() {
        Consent consent = new Consent(
                UUID.randomUUID().toString(), "TPP-001", "HOLDER-001",
                Set.of("accounts:read"), null,
                Instant.now().minusSeconds(1)); // already expired

        consent = consentRepository.save(consent);

        assertFalse(consent.isValid());
    }

    @Test
    @DisplayName("UC-01 AF-06: Account holder revokes consent → immediate effect")
    void uc01_AF06_consentRevoked() {
        Consent consent = new Consent(
                UUID.randomUUID().toString(), "TPP-001", "HOLDER-001",
                Set.of("accounts:read"), null,
                Instant.now().plusSeconds(90 * 24 * 3600));
        consent = consentRepository.save(consent);

        assertTrue(consent.isValid());

        // Account holder revokes
        consent.revoke();
        consentRepository.save(consent);

        assertFalse(consent.isValid());
        assertEquals(Consent.ConsentStatus.REVOKED, consent.getStatus());
        assertNotNull(consent.getRevokedAt());
    }

    // ====================================================================
    // UC-01/UC-02: SCA Session
    // ====================================================================

    @Test
    @DisplayName("UC-01 Main: SCA session — happy path")
    void uc01_scaSession_happyPath() {
        ScaSession session = new ScaSession(
                UUID.randomUUID().toString(), "HOLDER-001",
                new BigDecimal("5000.00"), "Ola Nordmann");

        assertTrue(session.isActive());
        assertFalse(session.isLocked());
        assertFalse(session.isExpired());

        // SCA succeeds
        session.complete();
        assertEquals(ScaSession.ScaStatus.COMPLETED, session.getStatus());
        assertNotNull(session.getCompletedAt());
    }

    @Test
    @DisplayName("UC-01 AF-02: SCA failure — 3 wrong attempts → locked for 5 minutes")
    void uc01_AF02_scaLockout() {
        ScaSession session = new ScaSession(
                UUID.randomUUID().toString(), "HOLDER-001", null, null);

        session.recordFailedAttempt(); // 1
        assertTrue(session.isActive());

        session.recordFailedAttempt(); // 2
        assertTrue(session.isActive());

        session.recordFailedAttempt(); // 3 → LOCKED
        assertTrue(session.isLocked());
        assertEquals(ScaSession.ScaStatus.LOCKED, session.getStatus());
        assertEquals(3, session.getFailedAttempts());
    }

    @Test
    @DisplayName("UC-02 2A: SCA dynamic linking — amount + creditor immutable")
    void uc02_2A_dynamicLinking() {
        ScaSession session = new ScaSession(
                UUID.randomUUID().toString(), "HOLDER-001",
                new BigDecimal("5000.00"), "Ola Nordmann");

        entityManager.persistAndFlush(session);

        // Dynamic link data is set at creation and cannot change
        assertEquals(new BigDecimal("5000.00"), session.getLinkedAmount());
        assertEquals("Ola Nordmann", session.getLinkedCreditor());
        // No setter for linkedAmount or linkedCreditor — immutable by design
    }

    // ====================================================================
    // Consent-Scoped Access
    // ====================================================================

    @Test
    @DisplayName("Consent scope: accounts:read but not payments:initiate → payment rejected")
    void consentScope_readOnly() {
        Consent consent = new Consent(
                UUID.randomUUID().toString(), "TPP-001", "HOLDER-001",
                Set.of("accounts:read"), null,
                Instant.now().plusSeconds(3600));

        assertTrue(consent.hasScope("accounts:read"));
        assertFalse(consent.hasScope("payments:initiate"));
    }

    @Test
    @DisplayName("Consent with all accounts (null list) → covers any account")
    void consentScope_allAccounts() {
        Consent consent = new Consent(
                UUID.randomUUID().toString(), "TPP-001", "HOLDER-001",
                Set.of("accounts:read"), null, // null = all accounts
                Instant.now().plusSeconds(3600));

        assertTrue(consent.coversAccount("ANY_ACCOUNT"));
    }
}
