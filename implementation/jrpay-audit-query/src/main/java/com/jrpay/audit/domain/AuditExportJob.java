package com.jrpay.audit.domain;

import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.Type;

@Entity
@Table(name = "audit_export_jobs")
public class AuditExportJob {

    @Id
    @Column(name = "request_id", nullable = false, updatable = false)
    private UUID requestId;

    @Column(name = "requested_by", nullable = false, length = 160)
    private String requestedBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "audience", nullable = false, length = 16)
    private Audience audience;

    @Enumerated(EnumType.STRING)
    @Column(name = "format", nullable = false, length = 16)
    private Format format;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private Status status;

    @Type(JsonBinaryType.class)
    @Column(name = "filter_json", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> filter;

    @Column(name = "requested_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime requestedAt;

    @Column(name = "ready_at")
    private OffsetDateTime readyAt;

    @Column(name = "download_expires_at")
    private OffsetDateTime downloadExpiresAt;

    @Column(name = "artefact_size_bytes")
    private Long artefactSizeBytes;

    @Column(name = "progress", nullable = false)
    private int progress;

    @Column(name = "signed", nullable = false)
    private boolean signed;

    @Column(name = "unsigned_reason", length = 64)
    private String unsignedReason;

    @Column(name = "failure_reason", columnDefinition = "TEXT")
    private String failureReason;

    @Column(name = "customer_user_id")
    private UUID customerUserId;

    @Column(name = "nonce", length = 64)
    private String nonce;

    @Column(name = "nonce_consumed_at")
    private OffsetDateTime nonceConsumedAt;

    protected AuditExportJob() {
    }

    public AuditExportJob(UUID requestId, String requestedBy, Audience audience, Format format,
                          Map<String, Object> filter, UUID customerUserId) {
        this.requestId = requestId;
        this.requestedBy = requestedBy;
        this.audience = audience;
        this.format = format;
        this.filter = filter;
        this.customerUserId = customerUserId;
        this.status = Status.PENDING;
        this.progress = 0;
    }

    public UUID getRequestId() { return requestId; }
    public String getRequestedBy() { return requestedBy; }
    public Audience getAudience() { return audience; }
    public Format getFormat() { return format; }
    public Status getStatus() { return status; }
    public Map<String, Object> getFilter() { return filter; }
    public OffsetDateTime getRequestedAt() { return requestedAt; }
    public OffsetDateTime getReadyAt() { return readyAt; }
    public OffsetDateTime getDownloadExpiresAt() { return downloadExpiresAt; }
    public Long getArtefactSizeBytes() { return artefactSizeBytes; }
    public int getProgress() { return progress; }
    public boolean isSigned() { return signed; }
    public String getUnsignedReason() { return unsignedReason; }
    public String getFailureReason() { return failureReason; }
    public UUID getCustomerUserId() { return customerUserId; }
    public String getNonce() { return nonce; }
    public OffsetDateTime getNonceConsumedAt() { return nonceConsumedAt; }

    public void markInProgress() { this.status = Status.IN_PROGRESS; }
    public void recordProgress(int p) { this.progress = Math.max(0, Math.min(100, p)); }

    public void markReady(long sizeBytes, OffsetDateTime expiresAt, String nonce,
                          boolean signed, String unsignedReason) {
        this.status = Status.READY;
        this.progress = 100;
        this.readyAt = OffsetDateTime.now();
        this.artefactSizeBytes = sizeBytes;
        this.downloadExpiresAt = expiresAt;
        this.nonce = nonce;
        this.signed = signed;
        this.unsignedReason = unsignedReason;
    }

    public void markFailed(String reason) {
        this.status = Status.FAILED;
        this.failureReason = reason;
    }

    public void markCancelled() { this.status = Status.CANCELLED; }
    public void markExpired() { this.status = Status.EXPIRED; }

    public void consumeNonce() {
        this.nonceConsumedAt = OffsetDateTime.now();
    }

    public boolean nonceLive() {
        return nonce != null && nonceConsumedAt == null
                && downloadExpiresAt != null && downloadExpiresAt.isAfter(OffsetDateTime.now());
    }

    public enum Audience { admin, customer }
    public enum Format { CSV, JSON_SIGNED }
    public enum Status { PENDING, IN_PROGRESS, READY, FAILED, EXPIRED, CANCELLED }
}
