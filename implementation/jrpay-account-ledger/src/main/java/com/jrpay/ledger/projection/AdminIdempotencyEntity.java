package com.jrpay.ledger.projection;

import jakarta.persistence.*;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Stored response for an {@code Idempotency-Key} replay. Scope is
 * {@code (operatorId, idempotencyKey)} — different operators reusing the same
 * key still each get their own first-write response.
 */
@Entity
@Table(name = "admin_idempotency")
@IdClass(AdminIdempotencyEntity.Pk.class)
public class AdminIdempotencyEntity {

    @Id
    @Column(name = "operator_id")
    private UUID operatorId;

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

    protected AdminIdempotencyEntity() {}

    public AdminIdempotencyEntity(UUID operatorId, String idempotencyKey, String operation,
                                  String requestHash, int responseStatus, String responseBody) {
        this.operatorId = operatorId;
        this.idempotencyKey = idempotencyKey;
        this.operation = operation;
        this.requestHash = requestHash;
        this.responseStatus = responseStatus;
        this.responseBody = responseBody;
        this.createdAt = Instant.now();
    }

    public UUID getOperatorId() { return operatorId; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getOperation() { return operation; }
    public String getRequestHash() { return requestHash; }
    public int getResponseStatus() { return responseStatus; }
    public String getResponseBody() { return responseBody; }
    public Instant getCreatedAt() { return createdAt; }

    public static class Pk implements Serializable {
        private UUID operatorId;
        private String idempotencyKey;

        public Pk() {}
        public Pk(UUID operatorId, String idempotencyKey) {
            this.operatorId = operatorId;
            this.idempotencyKey = idempotencyKey;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Pk pk)) return false;
            return Objects.equals(operatorId, pk.operatorId)
                    && Objects.equals(idempotencyKey, pk.idempotencyKey);
        }

        @Override
        public int hashCode() { return Objects.hash(operatorId, idempotencyKey); }
    }
}
