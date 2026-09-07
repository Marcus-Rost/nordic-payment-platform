package com.jrpay.identity.dev;

import com.jrpay.identity.domain.IdentityUser;
import com.jrpay.identity.repository.IdentityUserRepository;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * <strong>Development-only</strong> token endpoint for smoke tests. Active
 * solely under the {@code docker} / {@code demo} / {@code smoke} profiles —
 * production deployments must NEVER enable these profiles.
 *
 * <p>Trades a username + password for a freshly-signed access token, mirroring
 * the claims shape the real {@code authorization_code} pipeline produces
 * ({@code aud}, {@code acr}, {@code jrpay_uid}, {@code role}, {@code scope}).
 * The token is signed with the same JWK that drives {@code /oauth2/jwks} so
 * downstream resource servers (A1, A3, A4, A5, audit-query, notifications)
 * accept it without configuration changes.
 *
 * <p>This is purpose-built for {@code jrpay-smoke-tests}; it is not part of
 * the customer- or admin-facing OAuth2 surface.
 */
@RestController
@RequestMapping("/dev/oauth2")
@Profile({"docker", "demo", "smoke"})
public class DevTokenController {

    private final IdentityUserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final JwtEncoder jwtEncoder;
    private final String issuer;

    public DevTokenController(IdentityUserRepository users,
                              PasswordEncoder passwordEncoder,
                              JWKSource<SecurityContext> jwkSource,
                              @org.springframework.beans.factory.annotation.Value(
                                      "${jrpay.identity.issuer:http://localhost:8097}") String issuer) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.jwtEncoder = new NimbusJwtEncoder(jwkSource);
        this.issuer = issuer;
    }

    @PostMapping("/dev-token")
    public ResponseEntity<Map<String, Object>> issueToken(@Valid @RequestBody DevTokenRequest body) {
        IdentityUser.Audience aud = "admin".equalsIgnoreCase(body.audience())
                ? IdentityUser.Audience.admin
                : IdentityUser.Audience.customer;
        // Unknown user + wrong password both surface as 401 with the same
        // generic message — anti-enumeration practice. Audience mismatch and
        // suspended accounts are 403 (authenticated but not authorized for
        // the requested audience / account state).
        IdentityUser user = users.findByEmail(body.username())
                .or(() -> users.findByAudienceAndUsername(aud, body.username()))
                .orElseThrow(() -> new BadCredentialsException("Bad credentials"));
        if (!passwordEncoder.matches(body.password(), user.getPasswordHash())) {
            throw new BadCredentialsException("Bad credentials");
        }
        if (user.getAudience() != aud) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Audience mismatch");
        }
        if (user.getStatus() != IdentityUser.UserStatus.ACTIVE
                && user.getStatus() != IdentityUser.UserStatus.PENDING_FIRST_LOGIN) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "User not active: " + user.getStatus());
        }

        Instant now = Instant.now();
        Duration ttl = Duration.ofSeconds(body.expiresIn() == null ? 900 : body.expiresIn());
        String scope = body.scope() == null
                ? defaultScopeFor(aud) : body.scope();

        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .subject(user.getUsername())
                .audience(List.of(aud.name()))
                .issuedAt(now)
                .expiresAt(now.plus(ttl))
                .id(UUID.randomUUID().toString())
                .claim("jrpay_uid", user.getUserId().toString())
                .claim("acr", aud == IdentityUser.Audience.admin ? "loa3-admin" : "loa3")
                .claim("scope", scope);
        if (user.getRole() != null) {
            claims.claim("role", user.getRole());
        }

        var jwt = jwtEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(SignatureAlgorithm.RS256).build(),
                claims.build()));

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("access_token", jwt.getTokenValue());
        response.put("token_type", "Bearer");
        response.put("expires_in", ttl.getSeconds());
        response.put("scope", scope);
        return ResponseEntity.ok(response);
    }

    private static String defaultScopeFor(IdentityUser.Audience aud) {
        return aud == IdentityUser.Audience.admin
                ? "fraud:read fraud:manage sanctions:read sanctions:manage "
                + "accounts:admin:read accounts:admin:write "
                + "audit:read audit:verify "
                + "reconciliation:read reconciliation:run "
                + "payments:investigate gdpr:execute users:read users:manage system:read"
                : "openid profile accounts:read transactions:read "
                + "payments:initiate payments:read payments:cancel "
                + "mandates:manage consents:manage beneficiaries:manage "
                + "notifications:manage profile:read profile:write "
                + "gdpr:export gdpr:erase";
    }

    public record DevTokenRequest(
            @NotBlank String username,
            @NotBlank String password,
            @NotBlank String audience,
            String scope,
            Long expiresIn) { }
}
