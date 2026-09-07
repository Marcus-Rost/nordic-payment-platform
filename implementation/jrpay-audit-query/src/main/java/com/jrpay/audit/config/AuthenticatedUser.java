package com.jrpay.audit.config;

import java.util.Optional;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * Resolves the calling user-id + role from the active JWT. Mirrors the
 * conventions in identity-service / notifications-service:
 * <ul>
 *   <li>{@code jrpay_uid} — preferred custom claim carrying the user-id UUID.
 *   <li>{@code sub} — fallback (must be a UUID).
 *   <li>{@code aud} — single-value audience claim of {@code customer} or {@code admin}.
 * </ul>
 *
 * <p>The audit module uses the resolved user-id to scope the customer
 * consent-log surface (events where {@code customerUserId = caller}) and
 * the resolved role to gate admin endpoints.
 */
@Component
public class AuthenticatedUser {

    public Optional<UUID> currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (!(auth instanceof JwtAuthenticationToken jwtAuth)) return Optional.empty();
        Jwt jwt = jwtAuth.getToken();
        String uid = jwt.getClaimAsString("jrpay_uid");
        if (uid == null) uid = jwt.getSubject();
        if (uid == null) return Optional.empty();
        try {
            return Optional.of(UUID.fromString(uid));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public UUID requireUserId() {
        return currentUserId().orElseThrow(
                () -> new IllegalStateException("No authenticated user in security context"));
    }

    public String currentSubject() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (!(auth instanceof JwtAuthenticationToken jwtAuth)) return "system";
        var jwt = jwtAuth.getToken();
        String uid = jwt.getClaimAsString("jrpay_uid");
        if (uid == null) uid = jwt.getSubject();
        return uid == null ? "system" : (isAdmin() ? "operator:" + uid : "customer:" + uid);
    }

    public boolean isAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (!(auth instanceof JwtAuthenticationToken jwtAuth)) return false;
        var audiences = jwtAuth.getToken().getAudience();
        return audiences != null && audiences.contains("admin");
    }
}
