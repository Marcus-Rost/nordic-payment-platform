package com.jrpay.identity.api;

import com.jrpay.identity.domain.IdentityUser;
import com.jrpay.identity.passwordreset.PasswordPolicy;
import com.jrpay.identity.passwordreset.PasswordResetService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * UC-G44 password-reset endpoints. Mounted under {@code /oauth2/password-reset}
 * to match the spec; bypasses both OAuth2 and bearer auth (the reset URL token
 * is the credential).
 */
@RestController
@RequestMapping("/oauth2/password-reset")
public class PasswordResetController {

    private final PasswordResetService service;

    public PasswordResetController(PasswordResetService service) {
        this.service = service;
    }

    @PostMapping("/request")
    public ResponseEntity<Void> request(@Valid @RequestBody PasswordResetRequestBody body) {
        IdentityUser.Audience audience = "admin".equalsIgnoreCase(body.audience())
                ? IdentityUser.Audience.admin
                : IdentityUser.Audience.customer;
        var outcome = service.request(body.identifier(), audience);
        if (outcome == PasswordResetService.RequestOutcome.RATE_LIMITED) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build();
        }
        // Always 202 — neutral response per UC-G44 §6 step 4.
        return ResponseEntity.accepted().build();
    }

    @GetMapping("/verify")
    public ResponseEntity<Map<String, Object>> verify(@RequestParam("t") String token) {
        var outcome = service.verify(token);
        if (!outcome.valid()) {
            return ResponseEntity.status(HttpStatus.GONE).body(Map.of(
                    "type", "urn:problem:signed-url-expired",
                    "title", "Reset link expired",
                    "status", 410
            ));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("audience", outcome.audience().name());
        body.put("identifierMasked", outcome.identifierMasked());
        return ResponseEntity.ok(body);
    }

    @PostMapping("/complete")
    public ResponseEntity<Map<String, Object>> complete(@Valid @RequestBody PasswordResetCompleteBody body) {
        var outcome = service.complete(body.token(), body.newPassword());
        return switch (outcome.status()) {
            case OK -> ResponseEntity.noContent().build();
            case EXPIRED -> ResponseEntity.status(HttpStatus.GONE).body(Map.of(
                    "type", "urn:problem:signed-url-expired",
                    "title", "Reset link expired",
                    "status", 410
            ));
            case POLICY_FAILED -> {
                String type = outcome.policyResult() == PasswordPolicy.Result.BREACHED
                        ? "urn:problem:breached-password"
                        : "urn:problem:password-requirements-unmet";
                yield ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of(
                        "type", type,
                        "title", "Password does not meet policy",
                        "status", 422,
                        "detail", outcome.policyResult().name()
                ));
            }
        };
    }

    public record PasswordResetRequestBody(@NotBlank String identifier, String audience) { }

    public record PasswordResetCompleteBody(
            @NotBlank String token,
            @NotBlank @Size(min = 10, max = 128) String newPassword) { }
}
