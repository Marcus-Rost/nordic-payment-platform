package com.jrpay.identity.config;

import com.jrpay.identity.domain.IdentityUser;
import com.jrpay.identity.repository.IdentityUserRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * Resolves the JRPay user-id of the caller from the current Spring Security
 * context. Three resolution paths, in order:
 * <ol>
 *   <li>JWT — {@code jrpay_uid} custom claim, falling back to {@code sub}
 *       parsed as a UUID.
 *   <li>Form-login {@code Authentication.getName()} — used during the OAuth2
 *       authorize step where the principal is the username; we look up the
 *       user-id from the {@link IdentityUserRepository}.
 *   <li>Anonymous → empty.
 * </ol>
 */
@Component
public class AuthenticatedUser {

    private final IdentityUserRepository users;

    public AuthenticatedUser(IdentityUserRepository users) {
        this.users = users;
    }

    public Optional<UUID> currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) return Optional.empty();
        if (auth instanceof JwtAuthenticationToken jwtAuth) {
            Jwt jwt = jwtAuth.getToken();
            String uid = jwt.getClaimAsString("jrpay_uid");
            if (uid != null) {
                try { return Optional.of(UUID.fromString(uid)); }
                catch (IllegalArgumentException ignored) { /* fall through */ }
            }
            String sub = jwt.getSubject();
            if (sub != null) {
                try { return Optional.of(UUID.fromString(sub)); }
                catch (IllegalArgumentException ignored) {
                    return users.findByEmail(sub).map(IdentityUser::getUserId);
                }
            }
            return Optional.empty();
        }
        String name = auth.getName();
        if (name == null || "anonymousUser".equals(name)) return Optional.empty();
        return users.findByEmail(name)
                .or(() -> users.findByAudienceAndUsername(IdentityUser.Audience.customer, name))
                .or(() -> users.findByAudienceAndUsername(IdentityUser.Audience.admin, name))
                .map(IdentityUser::getUserId);
    }

    public UUID requireUserId() {
        return currentUserId().orElseThrow(
                () -> new IllegalStateException("No authenticated user in security context"));
    }
}
