package com.jrpay.billpay.domain;

import jakarta.persistence.*;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Per-customer Idempotency-Key dedup. Same shape as A1's
 * {@code admin_idempotency} but scoped to {@code customer_user_id}.
 */
@Entity
@Table(name = "customer_idempotency")
@IdClass(CustomerIdempotencyEntity.Pk.class)
public class CustomerIdempotencyEntity {

    @Id
    @Column(name = "customer_user_id")
    private UUID customerUserId;

    @Id
    @Column(name = "idempotency_key", length = 64)
    private String idempotencyKey;

    @Column(name = "operation", nullable = false, length = 40)
    private String operation;

    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Column(name = "response_status", nullable = false)
    private int responseStatus;

    @Column(name = "response_body", nullable = false, columnDefinition = "TEXT")
    private String responseBody;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected CustomerIdempotencyEntity() {}

    public CustomerIdempotencyEntity(UUID customerUserId, String idempotencyKey, String operation,
                                     String requestHash, int responseStatus, String responseBody) {
        this.customerUserId = customerUserId;
        this.idempotencyKey = idempotencyKey;
        this.operation = operation;
        this.requestHash = requestHash;
        this.responseStatus = responseStatus;
        this.responseBody = responseBody;
        this.createdAt = Instant.now();
    }

    public UUID getCustomerUserId() { return customerUserId; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getOperation() { return operation; }
    public String getRequestHash() { return requestHash; }
    public int getResponseStatus() { return responseStatus; }
    public String getResponseBody() { return responseBody; }
    public Instant getCreatedAt() { return createdAt; }

    public static class Pk implements Serializable {
        private UUID customerUserId;
        private String idempotencyKey;

        public Pk() {}
        public Pk(UUID customerUserId, String idempotencyKey) {
            this.customerUserId = customerUserId;
            this.idempotencyKey = idempotencyKey;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Pk pk)) return false;
            return Objects.equals(customerUserId, pk.customerUserId)
                    && Objects.equals(idempotencyKey, pk.idempotencyKey);
        }

        @Override
        public int hashCode() { return Objects.hash(customerUserId, idempotencyKey); }
    }
}
