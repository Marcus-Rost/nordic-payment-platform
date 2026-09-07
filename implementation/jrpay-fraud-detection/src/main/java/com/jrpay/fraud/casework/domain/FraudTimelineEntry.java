package com.jrpay.fraud.casework.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "fraud_timeline")
public class FraudTimelineEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "timeline_id")
    private Long timelineId;

    @Column(name = "case_id", nullable = false)
    private UUID caseId;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 32)
    private Type type;

    @Column(name = "actor", nullable = false, length = 160)
    private String actor;

    @Column(name = "note", length = 280)
    private String note;

    protected FraudTimelineEntry() {}

    public FraudTimelineEntry(UUID caseId, Type type, String actor, String note) {
        this.caseId = caseId;
        this.type = type;
        this.actor = actor;
        this.note = note;
        this.occurredAt = Instant.now();
    }

    public Long getTimelineId() { return timelineId; }
    public UUID getCaseId() { return caseId; }
    public Instant getOccurredAt() { return occurredAt; }
    public Type getType() { return type; }
    public String getActor() { return actor; }
    public String getNote() { return note; }

    public enum Type {
        CASE_CREATED, CASE_ASSIGNED, CASE_VIEWED, CASE_DECIDED,
        PII_REVEALED, SLA_BREACHED, PAYMENT_HELD, PAYMENT_RELEASED, PAYMENT_REJECTED
    }
}
