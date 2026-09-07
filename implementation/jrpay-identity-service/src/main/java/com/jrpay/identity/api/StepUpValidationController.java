package com.jrpay.identity.api;

import com.jrpay.identity.sca.ScaService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Internal endpoint for resource servers (A1, A4, A5) to validate a step-up
 * token presented by an admin/customer client. Single-use: a successful
 * validation consumes the token. Caller authenticates with a service-audience
 * JWT carrying the {@code sca:validate} scope.
 *
 * <p>Status mapping:
 * <ul>
 *   <li>{@code 200} — token valid; body contains {@code userId} and
 *       {@code dlHash}.
 *   <li>{@code 401 urn:problem:step-up-required} — token missing, unknown,
 *       or expired.
 *   <li>{@code 403 urn:problem:dynamic-linking-mismatch} — token live but
 *       bound to a different operation.
 * </ul>
 */
@RestController
@RequestMapping("/sca/step-up-tokens")
public class StepUpValidationController {

    private final ScaService sca;

    public StepUpValidationController(ScaService sca) {
        this.sca = sca;
    }

    @PostMapping(path = "/validate", consumes = MediaType.APPLICATION_JSON_VALUE,
                 produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('SCOPE_sca:validate')")
    public ResponseEntity<?> validate(@Valid @RequestBody StepUpValidateRequest body) {
        ScaService.ValidationResult result = sca.validateStepUp(body.token(), body.expectedDlHash());
        switch (result.outcome()) {
            case VALID -> {
                Map<String, Object> ok = new LinkedHashMap<>();
                ok.put("valid", true);
                ok.put("userId", result.userId() == null ? null : result.userId().toString());
                ok.put("scaSessionId", result.scaSessionId() == null ? null : result.scaSessionId().toString());
                ok.put("dlHash", result.dlHash());
                return ResponseEntity.ok(ok);
            }
            case DL_HASH_MISMATCH -> {
                return problem(HttpStatus.FORBIDDEN, "urn:problem:dynamic-linking-mismatch",
                        "Dynamic-linking mismatch",
                        "Step-up token bound to a different operation");
            }
            case NOT_FOUND, EXPIRED -> {
                return problem(HttpStatus.UNAUTHORIZED, "urn:problem:step-up-required",
                        "Step-up SCA required",
                        result.outcome() == ScaService.Outcome.EXPIRED
                                ? "Step-up token expired or already consumed"
                                : "Step-up token unknown");
            }
            default -> throw new IllegalStateException("Unhandled outcome: " + result.outcome());
        }
    }

    private static ResponseEntity<Map<String, Object>> problem(HttpStatus status, String type,
                                                               String title, String detail) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", type);
        body.put("title", title);
        body.put("status", status.value());
        body.put("detail", detail);
        body.put("instance", URI.create("urn:correlation:" + UUID.randomUUID()).toString());
        return ResponseEntity.status(status)
                .header("Content-Type", "application/problem+json")
                .body(body);
    }

    public record StepUpValidateRequest(@NotBlank String token, @NotBlank String expectedDlHash) { }
}
