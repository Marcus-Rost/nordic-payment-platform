package com.jrpay.ledger.projection;

import com.jrpay.ledger.domain.PiiField;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * Rate-limit-triggered approval slot. Lifecycle:
 * <pre>
 *   {@link #requestedAt} → {@link #approvedAt} → {@link #consumedAt}
 * </pre>
 * Consumption happens when the operator presents the {@code approvalRef} on
 * {@code POST /reveal-pii/approve}; the row is then immutable for audit.
 */
@Entity
@Table(name = "pii_pending_approvals")
public class PiiPendingApprovalEntity {

    @Id
    @Column(name = "approval_ref", length = 64)
    private String approvalRef;

    @Column(name = "account_id", nullable = false, length = 34)
    private String accountId;

    @Column(name = "operator_id", nullable = false)
    private UUID operatorId;

    @Enumerated(EnumType.STRING)
    @Column(name = "field", nullable = false, length = 20)
    private PiiField field;

    @Column(name = "justification_notes_hash", nullable = false, length = 64)
    private String justificationNotesHash;

    @Column(name = "notes_summary", nullable = false, length = 140)
    private String notesSummary;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "approved_by")
    private UUID approvedBy;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    protected PiiPendingApprovalEntity() {}

    public PiiPendingApprovalEntity(String approvalRef, String accountId, UUID operatorId, PiiField field,
                                    String justificationNotesHash, String notesSummary,
                                    Instant requestedAt, Instant expiresAt) {
        this.approvalRef = approvalRef;
        this.accountId = accountId;
        this.operatorId = operatorId;
        this.field = field;
        this.justificationNotesHash = justificationNotesHash;
        this.notesSummary = notesSummary;
        this.requestedAt = requestedAt;
        this.expiresAt = expiresAt;
    }

    public String getApprovalRef() { return approvalRef; }
    public String getAccountId() { return accountId; }
    public UUID getOperatorId() { return operatorId; }
    public PiiField getField() { return field; }
    public String getJustificationNotesHash() { return justificationNotesHash; }
    public String getNotesSummary() { return notesSummary; }
    public Instant getRequestedAt() { return requestedAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getApprovedAt() { return approvedAt; }
    public UUID getApprovedBy() { return approvedBy; }
    public Instant getConsumedAt() { return consumedAt; }

    public boolean isLive(Instant now) {
        return consumedAt == null && now.isBefore(expiresAt);
    }

    public boolean isApproved() { return approvedAt != null; }

    public void approve(UUID supervisorId, Instant at) {
        if (approvedAt != null) {
            throw new IllegalStateException("Already approved");
        }
        this.approvedAt = at;
        this.approvedBy = supervisorId;
    }

    public void consume(Instant at) {
        if (consumedAt != null) {
            throw new IllegalStateException("Already consumed");
        }
        this.consumedAt = at;
    }
}
