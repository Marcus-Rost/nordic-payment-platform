package com.jrpay.audit.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * Stores the rendered bytes of an export. Small CSVs / signed JSON bundles
 * live here; production deployments push large artefacts to object storage
 * and store only the URI.
 */
@Entity
@Table(name = "audit_export_artefacts")
public class AuditExportArtefact {

    @Id
    @Column(name = "request_id", nullable = false, updatable = false)
    private UUID requestId;

    @Column(name = "content_type", nullable = false, length = 64)
    private String contentType;

    @Column(name = "bytes", nullable = false, columnDefinition = "BYTEA")
    private byte[] bytes;

    protected AuditExportArtefact() {
    }

    public AuditExportArtefact(UUID requestId, String contentType, byte[] bytes) {
        this.requestId = requestId;
        this.contentType = contentType;
        this.bytes = bytes;
    }

    public UUID getRequestId() { return requestId; }
    public String getContentType() { return contentType; }
    public byte[] getBytes() { return bytes; }
}
