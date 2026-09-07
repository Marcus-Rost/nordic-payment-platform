package com.jrpay.audit.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "audit_checkpoints")
public class AuditCheckpoint {

    @Id
    @Column(name = "checkpoint_id", nullable = false, updatable = false)
    private UUID checkpointId;

    @Column(name = "from_offset", nullable = false)
    private long fromOffset;

    @Column(name = "to_offset", nullable = false)
    private long toOffset;

    @Column(name = "event_count", nullable = false)
    private int eventCount;

    @Column(name = "chain_hash", nullable = false, length = 64)
    private String chainHash;

    @Column(name = "verified_by", nullable = false, length = 64)
    private String verifiedBy;

    @Column(name = "verified_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime verifiedAt;

    protected AuditCheckpoint() {
    }

    public AuditCheckpoint(long fromOffset, long toOffset, int eventCount, String chainHash,
                           String verifiedBy) {
        this.checkpointId = UUID.randomUUID();
        this.fromOffset = fromOffset;
        this.toOffset = toOffset;
        this.eventCount = eventCount;
        this.chainHash = chainHash;
        this.verifiedBy = verifiedBy;
    }

    public UUID getCheckpointId() { return checkpointId; }
    public long getFromOffset() { return fromOffset; }
    public long getToOffset() { return toOffset; }
    public int getEventCount() { return eventCount; }
    public String getChainHash() { return chainHash; }
    public String getVerifiedBy() { return verifiedBy; }
    public OffsetDateTime getVerifiedAt() { return verifiedAt; }
}
