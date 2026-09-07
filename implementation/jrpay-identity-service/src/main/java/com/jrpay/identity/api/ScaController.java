package com.jrpay.identity.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.jrpay.identity.config.AuthenticatedUser;
import com.jrpay.identity.domain.ScaFactor;
import com.jrpay.identity.sca.ScaService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * SCA endpoints — UC-G51 (dynamic-linking SCA).
 */
@RestController
@RequestMapping("/sca")
public class ScaController {

    private final ScaService sca;
    private final AuthenticatedUser authenticatedUser;

    public ScaController(ScaService sca, AuthenticatedUser authenticatedUser) {
        this.sca = sca;
        this.authenticatedUser = authenticatedUser;
    }

    @PostMapping("/start")
    public ResponseEntity<Map<String, Object>> start(@Valid @RequestBody ScaStartRequest body) {
        UUID userId = authenticatedUser.requireUserId();
        ScaFactor.FactorType hint = body.factorHint() == null ? null
                : ScaFactor.FactorType.valueOf(body.factorHint());
        var result = sca.start(userId, body.inputs(), hint, false);
        Map<String, Object> response = new java.util.LinkedHashMap<>();
        response.put("scaSessionId", result.scaSessionId());
        response.put("dl_hash", result.dlHash());
        response.put("challenge", Map.of(
                "type", result.factorType().name(),
                "metadata", Map.of()));
        if (result.exempt()) {
            response.put("exempt", true);
            response.put("stepUpToken", result.stepUpToken());
        }
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{scaSessionId}/verify")
    public ResponseEntity<Map<String, Object>> verify(
            @PathVariable UUID scaSessionId,
            @Valid @RequestBody ScaVerifyRequest body) {
        ScaService.ScaProof proof = toProof(body.proof());
        var result = sca.verify(scaSessionId, proof);
        return ResponseEntity.ok(Map.of(
                "status", "VERIFIED",
                "stepUpToken", result.stepUpToken()
        ));
    }

    @PostMapping("/enroll")
    public ResponseEntity<Map<String, Object>> enroll(@Valid @RequestBody ScaEnrollRequest body) {
        UUID userId = authenticatedUser.requireUserId();
        ScaFactor.FactorType type = ScaFactor.FactorType.valueOf(body.factorType());
        var result = sca.enroll(userId, type);
        return ResponseEntity.status(HttpStatus.OK).body(Map.of(
                "enrollmentId", result.enrollmentId(),
                "setupPayload", Map.of(
                        "factorType", type.name(),
                        "secret", result.setupSecret(),
                        "issuer", "JRPay",
                        "label", "JRPay (" + userId + ")"
                )
        ));
    }

    private ScaService.ScaProof toProof(JsonNode proof) {
        if (proof == null || !proof.has("type")) {
            throw new IllegalArgumentException("proof.type is required");
        }
        String type = proof.get("type").asText();
        return switch (type) {
            case "TOTP" -> {
                if (!proof.has("code")) throw new IllegalArgumentException("proof.code missing");
                yield new ScaService.ScaProof.Totp(proof.get("code").asText());
            }
            case "BANKID_SIMULATED", "BANKID_REAL" -> {
                if (!proof.has("assertion")) throw new IllegalArgumentException("proof.assertion missing");
                yield new ScaService.ScaProof.BankId(proof.get("assertion").asText());
            }
            default -> throw new IllegalArgumentException("Unknown proof.type: " + type);
        };
    }

    public record ScaStartRequest(
            @NotNull JsonNode inputs,
            String factorHint
    ) { }

    public record ScaVerifyRequest(@NotNull JsonNode proof) { }

    public record ScaEnrollRequest(@NotNull String factorType) { }
}
