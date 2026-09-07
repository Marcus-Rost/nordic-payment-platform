package com.jrpay.fraud.casework.domain;

import jakarta.persistence.*;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "bulk_decide_runs")
@IdClass(BulkDecideRunEntity.Pk.class)
public class BulkDecideRunEntity {

    @Id
    @Column(name = "operator_user_id")
    private UUID operatorUserId;

    @Id
    @Column(name = "requested_at")
    private Instant requestedAt;

    @Column(name = "case_count", nullable = false)
    private int caseCount;

    @Column(name = "decision", nullable = false, length = 20)
    private String decision;

    protected BulkDecideRunEntity() {}

    public BulkDecideRunEntity(UUID operatorUserId, int caseCount, String decision) {
        this.operatorUserId = operatorUserId;
        this.requestedAt = Instant.now();
        this.caseCount = caseCount;
        this.decision = decision;
    }

    public UUID getOperatorUserId() { return operatorUserId; }
    public Instant getRequestedAt() { return requestedAt; }
    public int getCaseCount() { return caseCount; }
    public String getDecision() { return decision; }

    public static class Pk implements Serializable {
        private UUID operatorUserId;
        private Instant requestedAt;

        public Pk() {}
        public Pk(UUID operatorUserId, Instant requestedAt) {
            this.operatorUserId = operatorUserId;
            this.requestedAt = requestedAt;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Pk pk)) return false;
            return Objects.equals(operatorUserId, pk.operatorUserId)
                    && Objects.equals(requestedAt, pk.requestedAt);
        }

        @Override
        public int hashCode() { return Objects.hash(operatorUserId, requestedAt); }
    }
}
