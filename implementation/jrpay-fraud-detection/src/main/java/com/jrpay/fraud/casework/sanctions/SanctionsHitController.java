package com.jrpay.fraud.casework.sanctions;

import com.jrpay.fraud.casework.CaseworkProblemException;
import com.jrpay.fraud.casework.domain.SanctionsHitEntity;
import com.jrpay.fraud.casework.domain.StreamTokenEntity;
import com.jrpay.fraud.casework.sanctions.dto.FpCachePage;
import com.jrpay.fraud.casework.sanctions.dto.SanctionsDecideAck;
import com.jrpay.fraud.casework.sanctions.dto.SanctionsDecideRequest;
import com.jrpay.fraud.casework.sanctions.dto.SanctionsHitDetailDto;
import com.jrpay.fraud.casework.sanctions.dto.SanctionsHitPage;
import com.jrpay.fraud.casework.sanctions.dto.SanctionsListMetaDto;
import com.jrpay.fraud.casework.sanctions.dto.SanctionsListRefreshJobDto;
import com.jrpay.fraud.casework.stream.CaseworkStreamHub;
import com.jrpay.fraud.casework.stream.StreamTokenService;
import jakarta.validation.Valid;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1/admin/sanctions")
public class SanctionsHitController {

    private final SanctionsHitService service;
    private final StreamTokenService streamTokens;
    private final CaseworkStreamHub hub;

    public SanctionsHitController(SanctionsHitService service,
                                  StreamTokenService streamTokens,
                                  CaseworkStreamHub hub) {
        this.service = service;
        this.streamTokens = streamTokens;
        this.hub = hub;
    }

    @GetMapping("/hits")
    @PreAuthorize("hasAuthority('SCOPE_sanctions:read')")
    public ResponseEntity<SanctionsHitPage> list(
            @RequestParam(required = false) List<String> status,
            @RequestParam(name = "list", required = false) String listId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return ResponseEntity.ok(service.list(parseStatuses(status), listId, page, size));
    }

    @GetMapping("/hits/{hitId}")
    @PreAuthorize("hasAuthority('SCOPE_sanctions:read')")
    public ResponseEntity<SanctionsHitDetailDto> detail(@PathVariable UUID hitId) {
        return ResponseEntity.ok(service.detail(hitId));
    }

    @PostMapping("/hits/{hitId}/decide")
    @PreAuthorize("hasAuthority('SCOPE_sanctions:manage')")
    public ResponseEntity<SanctionsDecideAck> decide(
            @PathVariable UUID hitId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "Step-Up-Token", required = false) String stepUpToken,
            @Valid @RequestBody SanctionsDecideRequest body) {
        requireIdempotencyKey(idempotencyKey);
        return ResponseEntity.ok(service.decide(hitId, body, stepUpToken));
    }

    @GetMapping("/lists")
    @PreAuthorize("hasAuthority('SCOPE_sanctions:read')")
    public ResponseEntity<Map<String, Object>> listMetadata() {
        return ResponseEntity.ok(Map.of("items", service.listMetadata()));
    }

    @PostMapping("/lists/refresh")
    @PreAuthorize("hasAuthority('SCOPE_sanctions:manage')")
    public ResponseEntity<SanctionsListRefreshJobDto> refreshLists(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "Step-Up-Token", required = false) String stepUpToken,
            @RequestBody(required = false) RefreshRequest body) {
        requireIdempotencyKey(idempotencyKey);
        List<String> listIds = body == null ? null : body.listIds();
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(service.requestRefresh(listIds, stepUpToken));
    }

    @GetMapping("/lists/refresh/{runId}")
    @PreAuthorize("hasAuthority('SCOPE_sanctions:read')")
    public ResponseEntity<SanctionsListRefreshJobDto> getRefresh(@PathVariable UUID runId) {
        return ResponseEntity.ok(service.getRefresh(runId));
    }

    @GetMapping("/fp-cache")
    @PreAuthorize("hasAuthority('SCOPE_sanctions:read')")
    public ResponseEntity<FpCachePage> listFpCache(
            @RequestParam(required = false) String matchedEntity,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return ResponseEntity.ok(service.listFpCache(matchedEntity, page, size));
    }

    @DeleteMapping("/fp-cache/{entryId}")
    @PreAuthorize("hasAuthority('SCOPE_sanctions:manage')")
    public ResponseEntity<Void> removeFpEntry(
            @PathVariable UUID entryId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "Step-Up-Token", required = false) String stepUpToken) {
        requireIdempotencyKey(idempotencyKey);
        service.removeFpEntry(entryId, stepUpToken);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/hits/stream-token")
    @PreAuthorize("hasAuthority('SCOPE_sanctions:read')")
    public ResponseEntity<Map<String, Object>> mintStreamToken(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        requireIdempotencyKey(idempotencyKey);
        StreamTokenService.Issued issued = streamTokens.mint(StreamTokenEntity.Scope.SANCTIONS, null);
        return ResponseEntity.ok(Map.of(
                "streamToken", issued.token(),
                "expiresIn", issued.expiresInSeconds()));
    }

    @GetMapping(path = "/hits/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam("token") String token,
                             @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
        StreamTokenEntity entity = streamTokens.consume(token, StreamTokenEntity.Scope.SANCTIONS)
                .orElseThrow(StreamTokenService::invalidToken);
        SseEmitter emitter = new SseEmitter(0L);
        hub.register(CaseworkStreamHub.Scope.SANCTIONS, entity.getOperatorUserId(),
                entity.getResourceId(), emitter);
        try {
            emitter.send(SseEmitter.event().name("connected").data(Map.of(
                    "operatorUserId", entity.getOperatorUserId().toString(),
                    "lastEventId", lastEventId == null ? "" : lastEventId)));
        } catch (Exception ignored) { }
        return emitter;
    }

    public record RefreshRequest(List<String> listIds) {}

    private static void requireIdempotencyKey(String key) {
        if (key == null || key.isBlank()) {
            throw CaseworkProblemException.validation("Idempotency-Key header required");
        }
    }

    private static Collection<SanctionsHitEntity.Status> parseStatuses(List<String> raw) {
        if (raw == null || raw.isEmpty()) return List.of();
        List<SanctionsHitEntity.Status> out = new ArrayList<>();
        for (String s : raw) {
            try { out.add(SanctionsHitEntity.Status.valueOf(s)); }
            catch (IllegalArgumentException e) {
                throw CaseworkProblemException.invalidInput("Unknown status: " + s);
            }
        }
        return out;
    }
}
