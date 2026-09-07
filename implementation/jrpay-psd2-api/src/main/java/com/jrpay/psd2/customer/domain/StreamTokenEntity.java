package com.jrpay.psd2.customer.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * SSE stream token (single-use, 5-min TTL). Stored as SHA-256 of the token —
 * the wire token never lands on disk.
 */
@Entity
@Table(name = "stream_tokens")
public class StreamTokenEntity {

    @Id
    @Column(name = "token_hash", length = 64)
    private String tokenHash;

    @Column(name = "customer_user_id", nullable = false)
    private UUID customerUserId;

    @Column(name = "payment_id", length = 36)
    private String paymentId;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    protected StreamTokenEntity() {}

    public StreamTokenEntity(String tokenHash, UUID customerUserId, String paymentId,
                             Instant expiresAt) {
        this.tokenHash = tokenHash;
        this.customerUserId = customerUserId;
        this.paymentId = paymentId;
        this.issuedAt = Instant.now();
        this.expiresAt = expiresAt;
    }

    public String getTokenHash() { return tokenHash; }
    public UUID getCustomerUserId() { return customerUserId; }
    public String getPaymentId() { return paymentId; }
    public Instant getIssuedAt() { return issuedAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getConsumedAt() { return consumedAt; }

    public boolean isLive(Instant now) {
        return consumedAt == null && now.isBefore(expiresAt);
    }

    public void markConsumed() {
        this.consumedAt = Instant.now();
    }
}
