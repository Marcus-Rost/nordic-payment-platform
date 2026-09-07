package com.jrpay.fraud.casework.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "sanctions_fp_cache")
public class FpCacheEntity {

    @Id
    @Column(name = "entry_id")
    private UUID entryId;

    @Column(name = "matched_entity_name", nullable = false, length = 280)
    private String matchedEntityName;

    @Column(name = "matched_list_id", nullable = false, length = 64)
    private String matchedListId;

    @Column(name = "creditor_iban", nullable = false, length = 34)
    private String creditorIban;

    @Column(name = "notes_summary", length = 140)
    private String notesSummary;

    @Column(name = "added_by", nullable = false)
    private UUID addedBy;

    @Column(name = "source_hit_id")
    private UUID sourceHitId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "removed_at")
    private Instant removedAt;

    protected FpCacheEntity() {}

    public FpCacheEntity(UUID entryId, String matchedEntityName, String matchedListId,
                         String creditorIban, String notesSummary, UUID addedBy,
                         UUID sourceHitId, Instant expiresAt) {
        this.entryId = entryId;
        this.matchedEntityName = matchedEntityName;
        this.matchedListId = matchedListId;
        this.creditorIban = creditorIban;
        this.notesSummary = notesSummary;
        this.addedBy = addedBy;
        this.sourceHitId = sourceHitId;
        this.createdAt = Instant.now();
        this.expiresAt = expiresAt;
    }

    public UUID getEntryId() { return entryId; }
    public String getMatchedEntityName() { return matchedEntityName; }
    public String getMatchedListId() { return matchedListId; }
    public String getCreditorIban() { return creditorIban; }
    public String getNotesSummary() { return notesSummary; }
    public UUID getAddedBy() { return addedBy; }
    public UUID getSourceHitId() { return sourceHitId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getRemovedAt() { return removedAt; }

    public boolean isLive(Instant now) {
        return removedAt == null && now.isBefore(expiresAt);
    }

    public void remove() { this.removedAt = Instant.now(); }
}
