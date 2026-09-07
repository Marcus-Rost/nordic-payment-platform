package com.jrpay.fraud.casework.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "sanctions_list_refresh_runs")
public class SanctionsListRefreshRunEntity {

    @Id
    @Column(name = "run_id")
    private UUID runId;

    @Column(name = "requested_by", nullable = false)
    private UUID requestedBy;

    @Column(name = "list_ids", columnDefinition = "TEXT")
    private String listIds;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private Status status;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "failure_reason", length = 255)
    private String failureReason;

    protected SanctionsListRefreshRunEntity() {}

    public SanctionsListRefreshRunEntity(UUID runId, UUID requestedBy, String listIds) {
        this.runId = runId;
        this.requestedBy = requestedBy;
        this.listIds = listIds;
        this.status = Status.QUEUED;
        this.requestedAt = Instant.now();
    }

    public UUID getRunId() { return runId; }
    public UUID getRequestedBy() { return requestedBy; }
    public String getListIds() { return listIds; }
    public Status getStatus() { return status; }
    public Instant getRequestedAt() { return requestedAt; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public String getFailureReason() { return failureReason; }

    public void markRunning() {
        this.status = Status.RUNNING;
        this.startedAt = Instant.now();
    }

    public void markSucceeded() {
        this.status = Status.SUCCEEDED;
        this.completedAt = Instant.now();
    }

    public void markFailed(String reason) {
        this.status = Status.FAILED;
        this.completedAt = Instant.now();
        this.failureReason = reason;
    }

    public enum Status { QUEUED, RUNNING, SUCCEEDED, FAILED }
}
