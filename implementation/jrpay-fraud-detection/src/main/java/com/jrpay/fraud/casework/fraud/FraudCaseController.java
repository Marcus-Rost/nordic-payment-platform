package com.jrpay.fraud.casework.fraud;

import com.jrpay.fraud.casework.CaseworkProblemException;
import com.jrpay.fraud.casework.domain.FraudCaseEntity;
import com.jrpay.fraud.casework.domain.StreamTokenEntity;
import com.jrpay.fraud.casework.fraud.dto.FraudAssignRequest;
import com.jrpay.fraud.casework.fraud.dto.FraudBulkDecideAck;
import com.jrpay.fraud.casework.fraud.dto.FraudBulkDecideRequest;
import com.jrpay.fraud.casework.fraud.dto.FraudCaseContextDto;
import com.jrpay.fraud.casework.fraud.dto.FraudCaseDetailDto;
import com.jrpay.fraud.casework.fraud.dto.FraudCasePage;
import com.jrpay.fraud.casework.fraud.dto.FraudDecideAck;
import com.jrpay.fraud.casework.fraud.dto.FraudDecideRequest;
import com.jrpay.fraud.casework.stream.CaseworkStreamHub;
import com.jrpay.fraud.casework.stream.StreamTokenService;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1/admin/fraud")
public class FraudCaseController {

    private final FraudCaseService service;
    private final StreamTokenService streamTokens;
    private final CaseworkStreamHub hub;

    public FraudCaseController(FraudCaseService service,
                               StreamTokenService streamTokens,
                               CaseworkStreamHub hub) {
        this.service = service;
        this.streamTokens = streamTokens;
        this.hub = hub;
    }

    @GetMapping("/cases")
    @PreAuthorize("hasAuthority('SCOPE_fraud:read')")
    public ResponseEntity<FraudCasePage> list(
            @RequestParam(required = false) List<String> status,
            @RequestParam(required = false) BigDecimal minScore,
            @RequestParam(required = false) BigDecimal maxScore,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return ResponseEntity.ok(service.list(parseStatuses(status), minScore, maxScore, page, size));
    }

    @GetMapping("/cases/{caseId}")
    @PreAuthorize("hasAuthority('SCOPE_fraud:read')")
    public ResponseEntity<FraudCaseDetailDto> detail(@PathVariable UUID caseId) {
        return ResponseEntity.ok(service.detail(caseId));
    }

    @GetMapping("/cases/{caseId}/context")
    @PreAuthorize("hasAuthority('SCOPE_fraud:read')")
    public ResponseEntity<FraudCaseContextDto> context(@PathVariable UUID caseId) {
        return ResponseEntity.ok(service.context(caseId));
    }

    @PostMapping("/cases/{caseId}/assign")
    @PreAuthorize("hasAuthority('SCOPE_fraud:manage')")
    public ResponseEntity<FraudCaseDetailDto> assign(
            @PathVariable UUID caseId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody FraudAssignRequest body) {
        requireIdempotencyKey(idempotencyKey);
        return ResponseEntity.ok(service.assign(caseId, body));
    }

    @PostMapping("/cases/{caseId}/decide")
    @PreAuthorize("hasAuthority('SCOPE_fraud:manage')")
    public ResponseEntity<FraudDecideAck> decide(
            @PathVariable UUID caseId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "Step-Up-Token", required = false) String stepUpToken,
            @Valid @RequestBody FraudDecideRequest body) {
        requireIdempotencyKey(idempotencyKey);
        return ResponseEntity.ok(service.decide(caseId, body, stepUpToken));
    }

    @PostMapping("/cases/bulk-decide")
    @PreAuthorize("hasAuthority('SCOPE_fraud:manage')")
    public ResponseEntity<FraudBulkDecideAck> bulkDecide(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "Step-Up-Token", required = false) String stepUpToken,
            @Valid @RequestBody FraudBulkDecideRequest body) {
        requireIdempotencyKey(idempotencyKey);
        return ResponseEntity.ok(service.bulkDecide(body, stepUpToken));
    }

    @PostMapping("/cases/stream-token")
    @PreAuthorize("hasAuthority('SCOPE_fraud:read')")
    public ResponseEntity<Map<String, Object>> mintStreamToken(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody(required = false) StreamTokenRequest body) {
        requireIdempotencyKey(idempotencyKey);
        String resourceId = body == null || body.caseId() == null ? null : body.caseId().toString();
        StreamTokenService.Issued issued = streamTokens.mint(StreamTokenEntity.Scope.FRAUD, resourceId);
        return ResponseEntity.ok(Map.of(
                "streamToken", issued.token(),
                "expiresIn", issued.expiresInSeconds()));
    }

    @GetMapping(path = "/cases/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam("token") String token,
                             @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
        StreamTokenEntity entity = streamTokens.consume(token, StreamTokenEntity.Scope.FRAUD)
                .orElseThrow(StreamTokenService::invalidToken);
        SseEmitter emitter = new SseEmitter(0L);
        hub.register(CaseworkStreamHub.Scope.FRAUD, entity.getOperatorUserId(),
                entity.getResourceId(), emitter);
        try {
            emitter.send(SseEmitter.event().name("connected").data(Map.of(
                    "operatorUserId", entity.getOperatorUserId().toString(),
                    "lastEventId", lastEventId == null ? "" : lastEventId)));
        } catch (Exception ignored) { }
        return emitter;
    }

    public record StreamTokenRequest(UUID caseId) {}

    private static void requireIdempotencyKey(String key) {
        if (key == null || key.isBlank()) {
            throw CaseworkProblemException.validation("Idempotency-Key header required");
        }
    }

    private static Collection<FraudCaseEntity.Status> parseStatuses(List<String> raw) {
        if (raw == null || raw.isEmpty()) return List.of();
        List<FraudCaseEntity.Status> out = new ArrayList<>();
        for (String s : raw) {
            try { out.add(FraudCaseEntity.Status.valueOf(s)); }
            catch (IllegalArgumentException e) {
                throw CaseworkProblemException.invalidInput("Unknown status: " + s);
            }
        }
        return out;
    }
}
