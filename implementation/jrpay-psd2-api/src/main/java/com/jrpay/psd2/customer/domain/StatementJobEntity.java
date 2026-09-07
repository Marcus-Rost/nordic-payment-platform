package com.jrpay.psd2.customer.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "statement_jobs")
public class StatementJobEntity {

    @Id
    @Column(name = "request_id")
    private UUID requestId;

    @Column(name = "customer_user_id", nullable = false)
    private UUID customerUserId;

    @Column(name = "account_id", nullable = false, length = 34)
    private String accountId;

    @Column(name = "from_date", nullable = false)
    private LocalDate fromDate;

    @Column(name = "to_date", nullable = false)
    private LocalDate toDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "format", nullable = false, length = 8)
    private Format format;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private Status status;

    @Column(name = "download_url", length = 500)
    private String downloadUrl;

    @Column(name = "download_expires_at")
    private Instant downloadExpiresAt;

    @Column(name = "failure_reason", length = 255)
    private String failureReason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected StatementJobEntity() {}

    public StatementJobEntity(UUID requestId, UUID customerUserId, String accountId,
                              LocalDate fromDate, LocalDate toDate, Format format) {
        this.requestId = requestId;
        this.customerUserId = customerUserId;
        this.accountId = accountId;
        this.fromDate = fromDate;
        this.toDate = toDate;
        this.format = format;
        this.status = Status.PENDING;
        this.createdAt = Instant.now();
    }

    public UUID getRequestId() { return requestId; }
    public UUID getCustomerUserId() { return customerUserId; }
    public String getAccountId() { return accountId; }
    public LocalDate getFromDate() { return fromDate; }
    public LocalDate getToDate() { return toDate; }
    public Format getFormat() { return format; }
    public Status getStatus() { return status; }
    public String getDownloadUrl() { return downloadUrl; }
    public Instant getDownloadExpiresAt() { return downloadExpiresAt; }
    public String getFailureReason() { return failureReason; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getCompletedAt() { return completedAt; }

    public void markReady(String url, Instant expiresAt) {
        this.status = Status.READY;
        this.downloadUrl = url;
        this.downloadExpiresAt = expiresAt;
        this.completedAt = Instant.now();
    }

    public void markFailed(String reason) {
        this.status = Status.FAILED;
        this.failureReason = reason;
        this.completedAt = Instant.now();
    }

    public enum Format { PDF, CSV }
    public enum Status { PENDING, READY, FAILED, EXPIRED }
}
