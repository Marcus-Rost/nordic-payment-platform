package com.jrpay.billpay.domain;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * Append-only audit row for R2P state transitions. Surfaced to the customer
 * in {@code R2pDetail.history} (UC-G14).
 */
@Entity
@Table(name = "r2p_history")
public class R2pHistoryEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "history_id")
    private Long historyId;

    @Column(name = "request_id", nullable = false, length = 36)
    private String requestId;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private RequestStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "actor", nullable = false, length = 16)
    private Actor actor;

    @Column(name = "note", length = 280)
    private String note;

    protected R2pHistoryEntry() {} // JPA

    public R2pHistoryEntry(String requestId, RequestStatus status, Actor actor, String note) {
        this.requestId = requestId;
        this.status = status;
        this.actor = actor;
        this.note = note;
        this.occurredAt = Instant.now();
    }

    public Long getHistoryId() { return historyId; }
    public String getRequestId() { return requestId; }
    public Instant getOccurredAt() { return occurredAt; }
    public RequestStatus getStatus() { return status; }
    public Actor getActor() { return actor; }
    public String getNote() { return note; }

    public enum Actor { CUSTOMER, MERCHANT, SYSTEM }
}
