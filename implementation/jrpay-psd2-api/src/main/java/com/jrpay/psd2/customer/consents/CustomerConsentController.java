package com.jrpay.psd2.customer.consents;

import com.jrpay.psd2.consent.Consent;
import com.jrpay.psd2.consent.ConsentRepository;
import com.jrpay.psd2.customer.config.AuthenticatedUser;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * UC-G17 / UC-G18 — customer-side PSD2 consent management.
 *
 * <p>Hosted on psd2-api (port 8094). The customer-web SPA's
 * {@code ConsentsPage} hits {@code GET /api/v1/customer/consents} to list
 * the customer's active TPP consents and {@code DELETE} to revoke one.
 *
 * <p>Implementation gap caught by the 2026-04-27 GUI E2E run: the OpenAPI
 * spec listed these endpoints but the backend never implemented the
 * customer-facing surface (only the TPP-facing flows existed). The SPA
 * called the path and got 404 NoResourceFoundException.
 *
 * <p>Identification of the customer's consents uses the JWT subject /
 * jrpay_uid claim against the existing {@code accountHolderId} column. The
 * {@code Consent} entity is the same persistence model the TPP-facing
 * flows already use.
 */
@RestController
@RequestMapping("/api/v1/customer/consents")
public class CustomerConsentController {

    private final ConsentRepository consents;
    private final AuthenticatedUser caller;

    public CustomerConsentController(ConsentRepository consents, AuthenticatedUser caller) {
        this.consents = consents;
        this.caller = caller;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('SCOPE_consents:manage') or hasAuthority('SCOPE_consents:read')")
    public ResponseEntity<Map<String, Object>> list() {
        String holderId = resolveHolderId();
        List<Consent> active = consents.findByAccountHolderIdAndStatus(
                holderId, Consent.ConsentStatus.ACTIVE);
        Instant now = Instant.now();
        // Surface the same shape the SPA expects: items[].consentId, tppName,
        // consentType, expiresAt, status. tppName is the tppId here (we don't
        // have a separate display-name registry yet) so the SPA shows the id
        // verbatim until that is wired.
        List<Map<String, Object>> items = active.stream()
                .map(c -> Map.<String, Object>of(
                        "consentId", c.getConsentId(),
                        "tppId", c.getTppId(),
                        "tppName", c.getTppId(),
                        "consentType", inferType(c.getGrantedScopes()),
                        "scope", c.getGrantedScopes(),
                        "expiresAt", c.getExpiresAt().toString(),
                        "status", c.getExpiresAt().isBefore(now)
                                ? "EXPIRED"
                                : c.getStatus().name()))
                .toList();
        return ResponseEntity.ok(Map.of(
                "items", items,
                "page", Map.of(
                        "number", 0,
                        "size", items.size(),
                        "totalElements", items.size(),
                        "totalPages", items.isEmpty() ? 0 : 1)));
    }

    @DeleteMapping("/{consentId}")
    @PreAuthorize("hasAuthority('SCOPE_consents:manage') or hasAuthority('SCOPE_consents:revoke')")
    public ResponseEntity<Void> revoke(@PathVariable String consentId) {
        String holderId = resolveHolderId();
        Consent consent = consents.findById(consentId).orElse(null);
        if (consent == null || !holderId.equals(consent.getAccountHolderId())) {
            // 404 hides existence from a different customer (UC-G18 §6.3).
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        if (consent.getStatus() != Consent.ConsentStatus.ACTIVE) {
            // Revoking an already-revoked consent is a no-op; surface as 200.
            return ResponseEntity.noContent().build();
        }
        consent.revoke();
        consents.save(consent);
        return ResponseEntity.noContent().build();
    }

    private String resolveHolderId() {
        // The JWT's jrpay_uid (a UUID string) is what V003 seeds as the
        // account-holder id, but downstream callers may store the email-
        // shaped subject as well. We prefer jrpay_uid.toString() and fall
        // back to the JWT subject.
        return caller.currentUserId()
                .map(java.util.UUID::toString)
                .orElseGet(caller::currentSubject);
    }

    private String inferType(String grantedScopes) {
        if (grantedScopes == null) return "AISP";
        if (grantedScopes.contains("payments:initiate")) return "PISP";
        if (grantedScopes.contains("vrp")) return "VRP";
        return "AISP";
    }
}
