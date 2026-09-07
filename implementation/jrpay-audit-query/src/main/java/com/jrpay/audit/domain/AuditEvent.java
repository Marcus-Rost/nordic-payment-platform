package com.jrpay.audit.domain;

import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.Type;
import org.hibernate.generator.EventType;

/**
 * Projected row from the {@code audit.events} Kafka topic.
 *
 * <p>Each row gets a monotonic {@link #chainOffset} from a Postgres sequence
 * so the chain hash is stable across replay and can be re-verified offline
 * (UC-G40 §6.2 / §6.3). The hash chain is:
 *
 * <pre>
 * chainHash = sha256(canonicalJson(payload) || priorHash || chainOffset)
 * </pre>
 *
 * The first event has {@code priorHash=null}; subsequent events read the
 * predecessor's {@code chainHash} and feed it back in, so flipping a single
 * byte in any event invalidates every successor's hash — the verifier
 * surfaces the first mismatch + downstream offsets.
 */
@Entity
@Table(name = "audit_events")
public class AuditEvent {

    @Id
    @Column(name = "event_id", nullable = false, updatable = false, length = 26)
    private String eventId;

    @Column(name = "chain_offset", nullable = false, insertable = false, updatable = false)
    @Generated(event = EventType.INSERT)
    private Long chainOffset;

    @Column(name = "occurred_at", nullable = false)
    private OffsetDateTime occurredAt;

    @Column(name = "type", nullable = false, length = 120)
    private String type;

    @Column(name = "actor", nullable = false, length = 160)
    private String actor;

    @Enumerated(EnumType.STRING)
    @Column(name = "actor_kind", nullable = false, length = 16)
    private ActorKind actorKind;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", nullable = false, length = 16)
    private Outcome outcome;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", nullable = false, length = 16)
    private Severity severity;

    @Column(name = "summary")
    private String summary;

    @Column(name = "resource_type", length = 32)
    private String resourceType;

    @Column(name = "resource_id", length = 140)
    private String resourceId;

    @Column(name = "correlation_id")
    private UUID correlationId;

    @Column(name = "customer_user_id")
    private UUID customerUserId;

    @Column(name = "operator_user_id")
    private UUID operatorUserId;

    @Column(name = "consent_id")
    private UUID consentId;

    @Column(name = "payment_id")
    private UUID paymentId;

    @Column(name = "case_id")
    private UUID caseId;

    @Type(JsonBinaryType.class)
    @Column(name = "payload_json", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> payload;

    @Column(name = "prior_hash", length = 64)
    private String priorHash;

    @Column(name = "chain_hash", nullable = false, length = 64)
    private String chainHash;

    @Column(name = "kafka_topic", nullable = false, length = 120)
    private String kafkaTopic;

    @Column(name = "kafka_partition", nullable = false)
    private int kafkaPartition;

    @Column(name = "kafka_offset", nullable = false)
    private long kafkaOffset;

    @Column(name = "inserted_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime insertedAt;

    protected AuditEvent() {
    }

    AuditEvent(Builder b) {
        this.eventId = b.eventId;
        this.occurredAt = b.occurredAt;
        this.type = b.type;
        this.actor = b.actor;
        this.actorKind = b.actorKind;
        this.outcome = b.outcome;
        this.severity = b.severity;
        this.summary = b.summary;
        this.resourceType = b.resourceType;
        this.resourceId = b.resourceId;
        this.correlationId = b.correlationId;
        this.customerUserId = b.customerUserId;
        this.operatorUserId = b.operatorUserId;
        this.consentId = b.consentId;
        this.paymentId = b.paymentId;
        this.caseId = b.caseId;
        this.payload = b.payload;
        this.priorHash = b.priorHash;
        this.chainHash = b.chainHash;
        this.kafkaTopic = b.kafkaTopic;
        this.kafkaPartition = b.kafkaPartition;
        this.kafkaOffset = b.kafkaOffset;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getEventId() { return eventId; }
    public Long getChainOffset() { return chainOffset; }
    public OffsetDateTime getOccurredAt() { return occurredAt; }
    public String getType() { return type; }
    public String getActor() { return actor; }
    public ActorKind getActorKind() { return actorKind; }
    public Outcome getOutcome() { return outcome; }
    public Severity getSeverity() { return severity; }
    public String getSummary() { return summary; }
    public String getResourceType() { return resourceType; }
    public String getResourceId() { return resourceId; }
    public UUID getCorrelationId() { return correlationId; }
    public UUID getCustomerUserId() { return customerUserId; }
    public UUID getOperatorUserId() { return operatorUserId; }
    public UUID getConsentId() { return consentId; }
    public UUID getPaymentId() { return paymentId; }
    public UUID getCaseId() { return caseId; }
    public Map<String, Object> getPayload() { return payload; }
    public String getPriorHash() { return priorHash; }
    public String getChainHash() { return chainHash; }
    public String getKafkaTopic() { return kafkaTopic; }
    public int getKafkaPartition() { return kafkaPartition; }
    public long getKafkaOffset() { return kafkaOffset; }
    public OffsetDateTime getInsertedAt() { return insertedAt; }

    public enum ActorKind { operator, customer, system }
    public enum Outcome { SUCCESS, FAILURE, REJECTED }
    public enum Severity { INFO, WARN, ERROR, CRITICAL }

    public static class Builder {
        private String eventId;
        private OffsetDateTime occurredAt;
        private String type;
        private String actor;
        private ActorKind actorKind = ActorKind.system;
        private Outcome outcome = Outcome.SUCCESS;
        private Severity severity = Severity.INFO;
        private String summary;
        private String resourceType;
        private String resourceId;
        private UUID correlationId;
        private UUID customerUserId;
        private UUID operatorUserId;
        private UUID consentId;
        private UUID paymentId;
        private UUID caseId;
        private Map<String, Object> payload = Map.of();
        private String priorHash;
        private String chainHash;
        private String kafkaTopic;
        private int kafkaPartition;
        private long kafkaOffset;

        public Builder eventId(String v) { this.eventId = v; return this; }
        public Builder occurredAt(OffsetDateTime v) { this.occurredAt = v; return this; }
        public Builder type(String v) { this.type = v; return this; }
        public Builder actor(String v) { this.actor = v; return this; }
        public Builder actorKind(ActorKind v) { this.actorKind = v; return this; }
        public Builder outcome(Outcome v) { this.outcome = v; return this; }
        public Builder severity(Severity v) { this.severity = v; return this; }
        public Builder summary(String v) { this.summary = v; return this; }
        public Builder resourceType(String v) { this.resourceType = v; return this; }
        public Builder resourceId(String v) { this.resourceId = v; return this; }
        public Builder correlationId(UUID v) { this.correlationId = v; return this; }
        public Builder customerUserId(UUID v) { this.customerUserId = v; return this; }
        public Builder operatorUserId(UUID v) { this.operatorUserId = v; return this; }
        public Builder consentId(UUID v) { this.consentId = v; return this; }
        public Builder paymentId(UUID v) { this.paymentId = v; return this; }
        public Builder caseId(UUID v) { this.caseId = v; return this; }
        public Builder payload(Map<String, Object> v) { this.payload = v; return this; }
        public Builder priorHash(String v) { this.priorHash = v; return this; }
        public Builder chainHash(String v) { this.chainHash = v; return this; }
        public Builder kafkaTopic(String v) { this.kafkaTopic = v; return this; }
        public Builder kafkaPartition(int v) { this.kafkaPartition = v; return this; }
        public Builder kafkaOffset(long v) { this.kafkaOffset = v; return this; }
        public AuditEvent build() { return new AuditEvent(this); }
    }
}
