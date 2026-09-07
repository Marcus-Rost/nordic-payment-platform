package com.jrpay.ledger.projection;

import com.jrpay.ledger.domain.PiiField;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * Forensic record of a UC-G36 PII reveal. Each row corresponds to one
 * {@code POST /reveal-pii} (or {@code /reveal-pii/approve}) success. Extensions
 * increment {@link #extensionCount} on the same row but are also published as
 * separate {@code audit.pii-revealed.extended} audit events.
 */
@Entity
@Table(name = "pii_reveal_log")
public class PiiRevealEntity {

    @Id
    @Column(name = "reveal_id")
    private UUID revealId;

    @Column(name = "account_id", nullable = false, length = 34)
    private String accountId;

    @Column(name = "operator_id", nullable = false)
    private UUID operatorId;

    @Column(name = "customer_id")
    private UUID customerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "field", nullable = false, length = 20)
    private PiiField field;

    @Column(name = "revealed_at", nullable = false)
    private Instant revealedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "extension_count", nullable = false)
    private int extensionCount;

    @Column(name = "justification_notes_hash", nullable = false, length = 64)
    private String justificationNotesHash;

    @Column(name = "notes_summary", nullable = false, length = 140)
    private String notesSummary;

    @Column(name = "audit_event_id", nullable = false, length = 26)
    private String auditEventId;

    @Column(name = "sca_session_id")
    private UUID scaSessionId;

    @Column(name = "second_approval_ref", length = 64)
    private String secondApprovalRef;

    protected PiiRevealEntity() {}

    public PiiRevealEntity(UUID revealId, String accountId, UUID operatorId, UUID customerId,
                           PiiField field, Instant revealedAt, Instant expiresAt,
                           String justificationNotesHash, String notesSummary, String auditEventId,
                           UUID scaSessionId, String secondApprovalRef) {
        this.revealId = revealId;
        this.accountId = accountId;
        this.operatorId = operatorId;
        this.customerId = customerId;
        this.field = field;
        this.revealedAt = revealedAt;
        this.expiresAt = expiresAt;
        this.extensionCount = 0;
        this.justificationNotesHash = justificationNotesHash;
        this.notesSummary = notesSummary;
        this.auditEventId = auditEventId;
        this.scaSessionId = scaSessionId;
        this.secondApprovalRef = secondApprovalRef;
    }

    public UUID getRevealId() { return revealId; }
    public String getAccountId() { return accountId; }
    public UUID getOperatorId() { return operatorId; }
    public UUID getCustomerId() { return customerId; }
    public PiiField getField() { return field; }
    public Instant getRevealedAt() { return revealedAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public int getExtensionCount() { return extensionCount; }
    public String getJustificationNotesHash() { return justificationNotesHash; }
    public String getNotesSummary() { return notesSummary; }
    public String getAuditEventId() { return auditEventId; }
    public UUID getScaSessionId() { return scaSessionId; }
    public String getSecondApprovalRef() { return secondApprovalRef; }

    public void extend(Instant newExpiry) {
        if (extensionCount >= 3) {
            throw new IllegalStateException("Reveal extension cap reached");
        }
        this.extensionCount++;
        this.expiresAt = newExpiry;
    }
}
