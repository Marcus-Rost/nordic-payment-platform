package com.jrpay.psd2.customer.config;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

@Component
public class AuthenticatedUser {

    public Optional<UUID> currentUserId() {
        Jwt jwt = jwt().orElse(null);
        if (jwt == null) return Optional.empty();
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

    public boolean isCustomer() {
        return jwt()
                .map(Jwt::getAudience)
                .filter(a -> a != null && a.contains("customer"))
                .isPresent();
    }

    public boolean hasScope(String scope) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getAuthorities() == null) return false;
        String want = "SCOPE_" + scope;
        for (GrantedAuthority ga : auth.getAuthorities()) {
            if (want.equals(ga.getAuthority())) return true;
        }
        return false;
    }

    public List<String> currentScopes() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getAuthorities() == null) return List.of();
        return auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(a -> a.startsWith("SCOPE_"))
                .map(a -> a.substring("SCOPE_".length()))
                .toList();
    }

    public String currentSubject() {
        Jwt jwt = jwt().orElse(null);
        if (jwt == null) return "system";
        String uid = jwt.getClaimAsString("jrpay_uid");
        if (uid == null) uid = jwt.getSubject();
        if (uid == null) return "system";
        return isCustomer() ? "customer:" + uid : "operator:" + uid;
    }

    public Optional<String> currentRawJwt() {
        return jwt().map(Jwt::getTokenValue);
    }

    private Optional<Jwt> jwt() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken jwtAuth) {
            return Optional.of(jwtAuth.getToken());
        }
        return Optional.empty();
    }
}
