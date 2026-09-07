package com.jrpay.fraud.casework.domain;

import jakarta.persistence.*;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "fraud_held_payments")
@IdClass(FraudHeldPaymentEntity.Pk.class)
public class FraudHeldPaymentEntity {

    @Id
    @Column(name = "case_id")
    private UUID caseId;

    @Id
    @Column(name = "payment_id")
    private UUID paymentId;

    @Column(name = "amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @Column(name = "held_at", nullable = false)
    private Instant heldAt;

    protected FraudHeldPaymentEntity() {}

    public FraudHeldPaymentEntity(UUID caseId, UUID paymentId, BigDecimal amount,
                                  String currency, String status) {
        this.caseId = caseId;
        this.paymentId = paymentId;
        this.amount = amount;
        this.currency = currency;
        this.status = status;
        this.heldAt = Instant.now();
    }

    public UUID getCaseId() { return caseId; }
    public UUID getPaymentId() { return paymentId; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public String getStatus() { return status; }
    public Instant getHeldAt() { return heldAt; }

    public void setStatus(String s) { this.status = s; }

    public static class Pk implements Serializable {
        private UUID caseId;
        private UUID paymentId;

        public Pk() {}
        public Pk(UUID caseId, UUID paymentId) {
            this.caseId = caseId;
            this.paymentId = paymentId;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Pk pk)) return false;
            return Objects.equals(caseId, pk.caseId) && Objects.equals(paymentId, pk.paymentId);
        }

        @Override
        public int hashCode() { return Objects.hash(caseId, paymentId); }
    }
}
