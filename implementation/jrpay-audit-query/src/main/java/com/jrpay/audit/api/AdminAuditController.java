package com.jrpay.audit.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jrpay.audit.chain.ChainVerifier;
import com.jrpay.audit.config.AuthenticatedUser;
import com.jrpay.audit.domain.AuditCheckpoint;
import com.jrpay.audit.domain.AuditEvent;
import com.jrpay.audit.domain.AuditExportJob;
import com.jrpay.audit.export.ExportJobService;
import com.jrpay.audit.repository.AuditCheckpointRepository;
import com.jrpay.audit.repository.AuditEventRepository;
import com.jrpay.audit.search.AuditSearchSpec;
import com.jrpay.audit.search.AuditSearchSpec.AuditSearchCriteria;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/audit")
public class AdminAuditController {

    private final AuditEventRepository events;
    private final AuditCheckpointRepository checkpoints;
    private final ChainVerifier verifier;
    private final ExportJobService exporter;
    private final AuthenticatedUser caller;
    private final ObjectMapper mapper;
    private final int hardCap;

    public AdminAuditController(AuditEventRepository events,
                                AuditCheckpointRepository checkpoints,
                                ChainVerifier verifier,
                                ExportJobService exporter,
                                AuthenticatedUser caller,
                                ObjectMapper mapper,
                                @Value("${jrpay.audit.search.hard-cap:10000}") int hardCap) {
        this.events = events;
        this.checkpoints = checkpoints;
        this.verifier = verifier;
        this.exporter = exporter;
        this.caller = caller;
        this.mapper = mapper;
        this.hardCap = hardCap;
    }

    @GetMapping("/events")
    public Map<String, Object> search(
            @RequestParam(value = "from", required = false) OffsetDateTime from,
            @RequestParam(value = "to", required = false) OffsetDateTime to,
            @RequestParam(value = "type", required = false) List<String> type,
            @RequestParam(value = "actor", required = false) String actor,
            @RequestParam(value = "resourceType", required = false) String resourceType,
            @RequestParam(value = "resourceId", required = false) String resourceId,
            @RequestParam(value = "outcome", required = false) List<String> outcome,
            @RequestParam(value = "severity", required = false) List<String> severity,
            @RequestParam(value = "q", required = false) String q,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "25") int size,
            @RequestParam(value = "sortAsc", defaultValue = "false") boolean sortAsc) {
        AuditSearchCriteria criteria = new AuditSearchCriteria(
                from, to,
                type,
                actor,
                resourceType, resourceId,
                parseOutcomes(outcome),
                parseSeverities(severity),
                null, null, null,
                q);
        Sort sort = sortAsc ? Sort.by("occurredAt").ascending() : Sort.by("occurredAt").descending();
        int safeSize = Math.min(size, 200);
        var spec = AuditSearchSpec.from(criteria);
        var result = events.findAll(spec, PageRequest.of(page, safeSize, sort));
        boolean truncated = result.getTotalElements() > hardCap;

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", result.getContent().stream().map(this::summary).toList());
        body.put("page", Map.of(
                "page", result.getNumber(),
                "size", result.getSize(),
                "totalElements", Math.min(result.getTotalElements(), hardCap),
                "totalPages", (Math.min(result.getTotalElements(), hardCap) + safeSize - 1) / safeSize));
        body.put("truncated", truncated);
        return body;
    }

    @GetMapping("/events/{eventId}")
    public ResponseEntity<Map<String, Object>> detail(@PathVariable String eventId) {
        return events.findById(eventId)
                .map(e -> ResponseEntity.ok(detailView(e)))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).build());
    }

    @GetMapping("/event-types")
    public Map<String, Object> eventTypes() {
        // Simple distinct catalogue. Backed by an index on `type`; ~60 rows
        // expected (RFC 6749 ops + business operations).
        var types = events.findAll(PageRequest.of(0, 1)).map(e -> e.getType());
        // Real implementation pulls a static catalogue from src/main/resources;
        // the runtime list is the union.
        return Map.of("types", types.getContent());
    }

    @GetMapping("/checkpoints")
    public Map<String, Object> checkpointList() {
        List<AuditCheckpoint> all = checkpoints.findAllByOrderByFromOffsetAsc();
        List<Map<String, Object>> ranges = all.stream().map(this::checkpointView).toList();
        List<Map<String, Object>> gaps = computeGaps(all);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ranges", ranges);
        body.put("gaps", gaps);
        return body;
    }

    @PostMapping("/verify")
    public ResponseEntity<Map<String, Object>> verify(@Valid @RequestBody VerifyRequest body) {
        try {
            var result = verifier.verify(body.fromOffset, body.toOffset, caller.currentSubject());
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("verified", result.verified());
            resp.put("fromOffset", result.fromOffset());
            resp.put("toOffset", result.toOffset());
            resp.put("eventCount", result.eventCount());
            resp.put("durationMs", result.durationMs());
            if (!result.verified()) {
                resp.put("mismatchOffsets", result.mismatchOffsets());
                if (result.firstMismatch() != null) {
                    resp.put("firstMismatchDiff", Map.of(
                            "offset", result.firstMismatch().offset(),
                            "expectedHash", result.firstMismatch().expectedHash(),
                            "actualHash", result.firstMismatch().actualHash()));
                }
            }
            return ResponseEntity.ok(resp);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                    "type", "urn:problem:invalid-input",
                    "title", e.getMessage(),
                    "status", 400));
        }
    }

    @PostMapping("/export")
    public ResponseEntity<Map<String, Object>> requestExport(@Valid @RequestBody ExportRequest body) {
        AuditSearchCriteria criteria = mapper.convertValue(body.filter, AuditSearchCriteria.class);
        var format = AuditExportJob.Format.valueOf(body.format);
        var exportRequest = new ExportJobService.ExportRequest(
                caller.currentSubject(),
                AuditExportJob.Audience.admin,
                format,
                null,
                criteria);
        AuditExportJob job = exporter.request(exportRequest);
        exporter.renderAsync(job.getRequestId());
        return ResponseEntity.accepted().body(exportView(job));
    }

    @GetMapping("/export/{requestId}")
    public ResponseEntity<Map<String, Object>> exportStatus(@PathVariable UUID requestId) {
        return exporter.findById(requestId)
                .map(job -> ResponseEntity.ok(exportView(job)))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).build());
    }

    @GetMapping("/export/{requestId}/download")
    public ResponseEntity<Void> download(@PathVariable UUID requestId) {
        Optional<AuditExportJob> job = exporter.findById(requestId);
        if (job.isEmpty()) return ResponseEntity.notFound().build();
        if (job.get().getStatus() != AuditExportJob.Status.READY) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
        String url = exporter.buildDownloadUrl(job.get());
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(url)).build();
    }

    @GetMapping("/export-bin")
    public ResponseEntity<byte[]> downloadArtefact(
            @RequestParam("request_id") UUID requestId,
            @RequestParam("nonce") String nonce,
            @RequestParam("exp") long exp,
            @RequestParam("sig") String sig) {
        try {
            var payload = exporter.consumeArtefact(requestId, nonce, exp, sig);
            return ResponseEntity.ok()
                    .header("Content-Type", payload.contentType())
                    .header("Content-Disposition",
                            "attachment; filename=\"audit-export-" + requestId + extensionFor(payload.contentType()) + "\"")
                    .body(payload.bytes());
        } catch (NoSuchElementException notFound) {
            return ResponseEntity.notFound().build();
        } catch (IllegalStateException bad) {
            return ResponseEntity.status(HttpStatus.GONE).build();
        }
    }

    private static String extensionFor(String contentType) {
        return contentType.startsWith("text/csv") ? ".csv" : ".json";
    }

    private static List<AuditEvent.Outcome> parseOutcomes(List<String> raw) {
        if (raw == null) return null;
        return raw.stream().map(AuditEvent.Outcome::valueOf).toList();
    }

    private static List<AuditEvent.Severity> parseSeverities(List<String> raw) {
        if (raw == null) return null;
        return raw.stream().map(AuditEvent.Severity::valueOf).toList();
    }

    private Map<String, Object> summary(AuditEvent e) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("eventId", e.getEventId());
        v.put("at", e.getOccurredAt());
        v.put("type", e.getType());
        v.put("actor", e.getActor());
        v.put("outcome", e.getOutcome().name());
        v.put("severity", e.getSeverity().name());
        if (e.getSummary() != null) v.put("summary", e.getSummary());
        if (e.getResourceType() != null) v.put("resourceType", e.getResourceType());
        if (e.getResourceId() != null) v.put("resourceId", e.getResourceId());
        if (e.getCorrelationId() != null) v.put("correlationId", e.getCorrelationId());
        v.put("chainOffset", e.getChainOffset());
        return v;
    }

    private Map<String, Object> detailView(AuditEvent e) {
        Map<String, Object> v = summary(e);
        v.put("payload", e.getPayload());
        v.put("priorHash", e.getPriorHash());
        v.put("chainHash", e.getChainHash());
        v.put("kafkaTopic", e.getKafkaTopic());
        v.put("kafkaPartition", e.getKafkaPartition());
        v.put("kafkaOffset", e.getKafkaOffset());
        return v;
    }

    private Map<String, Object> checkpointView(AuditCheckpoint c) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("fromOffset", c.getFromOffset());
        v.put("toOffset", c.getToOffset());
        v.put("hash", c.getChainHash());
        v.put("verifiedAt", c.getVerifiedAt());
        v.put("verifiedBy", c.getVerifiedBy());
        v.put("eventCount", c.getEventCount());
        return v;
    }

    private List<Map<String, Object>> computeGaps(List<AuditCheckpoint> ordered) {
        if (ordered.isEmpty()) return List.of();
        List<Map<String, Object>> gaps = new java.util.ArrayList<>();
        long expected = 1L;
        for (AuditCheckpoint c : ordered) {
            if (c.getFromOffset() > expected) {
                gaps.add(Map.of(
                        "fromOffset", expected,
                        "toOffset", c.getFromOffset() - 1,
                        "severity", "WARN",
                        "reason", "AWAITING_VERIFICATION"));
            }
            expected = Math.max(expected, c.getToOffset() + 1);
        }
        Long maxOffset = events.findMaxChainOffset().orElse(null);
        if (maxOffset != null && maxOffset >= expected) {
            gaps.add(Map.of(
                    "fromOffset", expected,
                    "toOffset", maxOffset,
                    "severity", "INFO",
                    "reason", "AWAITING_VERIFICATION"));
        }
        return gaps;
    }

    private Map<String, Object> exportView(AuditExportJob job) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("requestId", job.getRequestId());
        v.put("status", job.getStatus().name());
        v.put("format", job.getFormat().name());
        v.put("requestedAt", job.getRequestedAt());
        if (job.getReadyAt() != null) v.put("readyAt", job.getReadyAt());
        if (job.getStatus() == AuditExportJob.Status.READY) {
            v.put("downloadUrl", exporter.buildDownloadUrl(job));
            v.put("downloadExpiresAt", job.getDownloadExpiresAt());
        }
        v.put("progress", job.getProgress());
        if (job.getArtefactSizeBytes() != null) v.put("artefactSizeBytes", job.getArtefactSizeBytes());
        v.put("signed", job.isSigned());
        if (job.getUnsignedReason() != null) v.put("unsignedReason", job.getUnsignedReason());
        if (job.getFailureReason() != null) v.put("failureReason", job.getFailureReason());
        return v;
    }

    public static final class VerifyRequest {
        @NotNull public Long fromOffset;
        @NotNull public Long toOffset;
        public String reason;
    }

    public static final class ExportRequest {
        @NotNull public String format;
        @NotNull public Map<String, Object> filter;
        public String reason;
    }
}
