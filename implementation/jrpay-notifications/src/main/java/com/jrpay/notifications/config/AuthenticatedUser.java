package com.jrpay.notifications.config;

import com.jrpay.notifications.domain.Audience;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * Resolves the calling user-id + audience from the current JWT.
 *
 * <p>Conventions inherited from {@code identity-service}:
 * <ul>
 *   <li>{@code jrpay_uid} — preferred custom claim carrying the user-id UUID.
 *   <li>{@code sub} — fallback (must be a UUID).
 *   <li>{@code aud} — single-value audience claim of {@code customer} or {@code admin}.
 * </ul>
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

    public Audience currentAudience() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (!(auth instanceof JwtAuthenticationToken jwtAuth)) return Audience.customer;
        var audiences = jwtAuth.getToken().getAudience();
        if (audiences != null && audiences.contains("admin")) return Audience.admin;
        return Audience.customer;
    }
}
