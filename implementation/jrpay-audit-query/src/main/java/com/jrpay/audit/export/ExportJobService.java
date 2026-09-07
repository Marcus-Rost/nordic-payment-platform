package com.jrpay.audit.export;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jrpay.audit.domain.AuditEvent;
import com.jrpay.audit.domain.AuditExportArtefact;
import com.jrpay.audit.domain.AuditExportJob;
import com.jrpay.audit.repository.AuditEventRepository;
import com.jrpay.audit.repository.AuditExportArtefactRepository;
import com.jrpay.audit.repository.AuditExportJobRepository;
import com.jrpay.audit.search.AuditSearchSpec;
import com.jrpay.audit.search.AuditSearchSpec.AuditSearchCriteria;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestrates audit-export jobs end-to-end:
 * <ol>
 *   <li>{@link #request(ExportRequest)} persists a {@code PENDING} job and
 *       fires the renderer asynchronously so the caller's HTTP request
 *       returns immediately with a tracking id (UC-G40 §6.4 / contract A6).</li>
 *   <li>{@link #renderAsync(UUID)} runs in a separate thread, executes the
 *       search criteria, materialises the artefact (CSV or signed JSON),
 *       writes the bytes, and marks the job {@code READY} with a single-use
 *       nonce + signed download URL.</li>
 *   <li>{@link #consumeArtefact(UUID, String, long, String)} verifies the
 *       URL signature + nonce and atomically returns the artefact bytes
 *       (used by the public download endpoint).</li>
 * </ol>
 *
 * <p>The artefact bytes are stored inline in {@code audit_export_artefacts}
 * for the portfolio default. A production deployment would push large
 * artefacts to object storage and persist only the URI; the rest of the
 * flow stays the same.
 */
@Service
public class ExportJobService {

    private static final Logger log = LoggerFactory.getLogger(ExportJobService.class);
    private static final SecureRandom RNG = new SecureRandom();

    private final AuditExportJobRepository jobs;
    private final AuditExportArtefactRepository artefacts;
    private final AuditEventRepository events;
    private final SignedUrlService signer;
    private final ExportConfig config;
    private final ObjectMapper mapper;

    public ExportJobService(AuditExportJobRepository jobs,
                            AuditExportArtefactRepository artefacts,
                            AuditEventRepository events,
                            SignedUrlService signer,
                            ExportConfig config,
                            ObjectMapper mapper) {
        this.jobs = jobs;
        this.artefacts = artefacts;
        this.events = events;
        this.signer = signer;
        this.config = config;
        this.mapper = mapper;
    }

    @Transactional
    public AuditExportJob request(ExportRequest request) {
        UUID requestId = UUID.randomUUID();
        Map<String, Object> filterMap = mapper.convertValue(request.criteria(), Map.class);
        AuditExportJob job = new AuditExportJob(
                requestId,
                request.requestedBy(),
                request.audience(),
                request.format(),
                filterMap,
                request.customerUserId());
        jobs.save(job);
        log.info("audit export queued requestId={} format={} requestedBy={}",
                requestId, request.format(), request.requestedBy());
        return job;
    }

    @Transactional(readOnly = true)
    public Optional<AuditExportJob> findById(UUID requestId) {
        return jobs.findById(requestId);
    }

    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void renderAsync(UUID requestId) {
        AuditExportJob job = jobs.findById(requestId).orElse(null);
        if (job == null) {
            log.warn("renderAsync: job {} disappeared before render", requestId);
            return;
        }
        try {
            job.markInProgress();
            jobs.save(job);

            List<AuditEvent> rows = loadRows(job);
            byte[] bytes;
            String contentType;
            boolean signed;
            String unsignedReason = null;
            switch (job.getFormat()) {
                case CSV -> {
                    bytes = CsvExportWriter.render(rows);
                    contentType = "text/csv; charset=utf-8";
                    signed = false;
                    unsignedReason = "FORMAT_NOT_SIGNED";
                }
                case JSON_SIGNED -> {
                    bytes = SignedJsonExportWriter.render(
                            job.getRequestId(), rows, config.signingKey(), mapper);
                    contentType = "application/json";
                    signed = true;
                }
                default -> throw new IllegalStateException("Unknown format: " + job.getFormat());
            }

            artefacts.save(new AuditExportArtefact(requestId, contentType, bytes));

            String nonce = randomNonce();
            OffsetDateTime expiresAt = OffsetDateTime.now().plus(config.signedUrlTtl());
            job.markReady(bytes.length, expiresAt, nonce, signed, unsignedReason);
            jobs.save(job);
            log.info("audit export ready requestId={} bytes={} signed={}",
                    requestId, bytes.length, signed);
        } catch (Exception e) {
            log.error("audit export failed requestId={}: {}", requestId, e.toString(), e);
            try {
                job.markFailed(e.getClass().getSimpleName() + ": " + e.getMessage());
                jobs.save(job);
            } catch (Exception ignored) {
                // Last-resort save failure — a follow-up sweep will mark stuck jobs FAILED.
            }
        }
    }

    /**
     * Returns the artefact bytes for {@code requestId} after verifying the
     * download URL parameters. Marks the nonce consumed atomically — a
     * second call with the same URL will see {@code NONCE_CONSUMED}.
     */
    @Transactional
    public ArtefactPayload consumeArtefact(UUID requestId, String nonce, long exp, String sig) {
        AuditExportJob job = jobs.findById(requestId)
                .orElseThrow(() -> new NoSuchElementException("export job not found"));
        if (job.getStatus() != AuditExportJob.Status.READY) {
            throw new IllegalStateException("EXPORT_NOT_READY:" + job.getStatus());
        }
        if (!nonce.equals(job.getNonce())) {
            throw new IllegalStateException("NONCE_MISMATCH");
        }
        if (job.getNonceConsumedAt() != null) {
            throw new IllegalStateException("NONCE_CONSUMED");
        }
        if (job.getDownloadExpiresAt() != null
                && job.getDownloadExpiresAt().isBefore(OffsetDateTime.now())) {
            job.markExpired();
            jobs.save(job);
            throw new IllegalStateException("EXPORT_EXPIRED");
        }
        if (exp < OffsetDateTime.now().toEpochSecond()) {
            throw new IllegalStateException("URL_EXPIRED");
        }
        if (!signer.verify(requestId, nonce, exp, sig)) {
            throw new IllegalStateException("BAD_SIGNATURE");
        }
        AuditExportArtefact artefact = artefacts.findById(requestId)
                .orElseThrow(() -> new IllegalStateException("artefact missing"));
        job.consumeNonce();
        jobs.save(job);
        return new ArtefactPayload(artefact.getContentType(), artefact.getBytes());
    }

    public String buildDownloadUrl(AuditExportJob job) {
        if (job.getStatus() != AuditExportJob.Status.READY) return null;
        return signer.buildDownloadUrl(
                job.getRequestId(), job.getNonce(), job.getDownloadExpiresAt());
    }

    private List<AuditEvent> loadRows(AuditExportJob job) {
        AuditSearchCriteria criteria = mapper.convertValue(job.getFilter(), AuditSearchCriteria.class);
        var spec = AuditSearchSpec.from(criteria);
        var page = events.findAll(
                spec,
                PageRequest.of(0, config.maxRows(), Sort.by("occurredAt").ascending()));
        return page.getContent();
    }

    private static String randomNonce() {
        byte[] raw = new byte[24];
        RNG.nextBytes(raw);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    public record ExportRequest(
            String requestedBy,
            AuditExportJob.Audience audience,
            AuditExportJob.Format format,
            UUID customerUserId,
            AuditSearchCriteria criteria) { }

    public record ArtefactPayload(String contentType, byte[] bytes) { }
}
