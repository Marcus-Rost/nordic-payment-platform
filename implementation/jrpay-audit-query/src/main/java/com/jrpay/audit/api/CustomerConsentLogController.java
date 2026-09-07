package com.jrpay.audit.api;

import com.jrpay.audit.config.AuthenticatedUser;
import com.jrpay.audit.domain.AuditEvent;
import com.jrpay.audit.domain.AuditExportJob;
import com.jrpay.audit.export.ExportJobService;
import com.jrpay.audit.repository.AuditEventRepository;
import com.jrpay.audit.search.AuditSearchSpec;
import com.jrpay.audit.search.AuditSearchSpec.AuditSearchCriteria;
import jakarta.validation.Valid;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Customer-facing slice of the audit projection: every event whose
 * {@code customerUserId} matches the JWT subject (UC-G26 §6 step 2).
 * Filters narrower than the admin endpoint — only the fields a customer
 * has visibility into.
 */
@RestController
@RequestMapping("/api/v1/customer/consent-log")
public class CustomerConsentLogController {

    private final AuditEventRepository events;
    private final ExportJobService exporter;
    private final AuthenticatedUser caller;

    public CustomerConsentLogController(AuditEventRepository events,
                                        ExportJobService exporter,
                                        AuthenticatedUser caller) {
        this.events = events;
        this.exporter = exporter;
        this.caller = caller;
    }

    @GetMapping
    public Map<String, Object> list(
            @RequestParam(value = "from", required = false) OffsetDateTime from,
            @RequestParam(value = "to", required = false) OffsetDateTime to,
            @RequestParam(value = "type", required = false) List<String> type,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "25") int size) {
        UUID userId = caller.requireUserId();
        AuditSearchCriteria criteria = new AuditSearchCriteria(
                from, to, type, null, null, null,
                null, null, userId, null, null, null);
        Sort sort = Sort.by("occurredAt").descending();
        int safeSize = Math.min(size, 100);
        var spec = AuditSearchSpec.from(criteria);
        var result = events.findAll(spec, PageRequest.of(page, safeSize, sort));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", result.getContent().stream().map(this::view).toList());
        body.put("page", Map.of(
                "page", result.getNumber(),
                "size", result.getSize(),
                "totalElements", result.getTotalElements(),
                "totalPages", result.getTotalPages()));
        return body;
    }

    @GetMapping("/{eventId}")
    public ResponseEntity<Map<String, Object>> detail(@PathVariable String eventId) {
        UUID userId = caller.requireUserId();
        Specification<AuditEvent> idAndOwner = (root, query, cb) -> cb.and(
                cb.equal(root.get("eventId"), eventId),
                cb.equal(root.get("customerUserId"), userId));
        return events.findOne(idAndOwner)
                .<ResponseEntity<Map<String, Object>>>map(e -> ResponseEntity.ok(view(e)))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).build());
    }

    @PostMapping("/export")
    public ResponseEntity<Map<String, Object>> requestExport(@Valid @RequestBody ExportRequest body) {
        UUID userId = caller.requireUserId();
        AuditSearchCriteria criteria = new AuditSearchCriteria(
                body.from, body.to, body.type, null, null, null,
                null, null, userId, null, null, null);
        var exportRequest = new ExportJobService.ExportRequest(
                "customer:" + userId,
                AuditExportJob.Audience.customer,
                AuditExportJob.Format.CSV,
                userId,
                criteria);
        AuditExportJob job = exporter.request(exportRequest);
        exporter.renderAsync(job.getRequestId());
        return ResponseEntity.accepted().body(exportView(job));
    }

    @GetMapping("/export/{requestId}")
    public ResponseEntity<Map<String, Object>> exportStatus(@PathVariable UUID requestId) {
        UUID userId = caller.requireUserId();
        return exporter.findById(requestId)
                .filter(job -> userId.equals(job.getCustomerUserId()))
                .map(job -> ResponseEntity.ok(exportView(job)))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).build());
    }

    private Map<String, Object> view(AuditEvent e) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("eventId", e.getEventId());
        v.put("at", e.getOccurredAt());
        v.put("type", e.getType());
        v.put("actor", e.getActor());
        v.put("outcome", e.getOutcome().name());
        if (e.getSummary() != null) v.put("summary", e.getSummary());
        if (e.getConsentId() != null) v.put("consentId", e.getConsentId());
        if (e.getPaymentId() != null) v.put("paymentId", e.getPaymentId());
        return v;
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
        if (job.getFailureReason() != null) v.put("failureReason", job.getFailureReason());
        return v;
    }

    public static final class ExportRequest {
        public OffsetDateTime from;
        public OffsetDateTime to;
        public List<String> type;
    }
}
