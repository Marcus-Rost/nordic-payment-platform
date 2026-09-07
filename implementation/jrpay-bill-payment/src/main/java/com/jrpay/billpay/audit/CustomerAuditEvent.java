package com.jrpay.billpay.audit;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Audit event emitted by A3's customer surface. */
public record CustomerAuditEvent(
        String type,
        Instant occurredAt,
        String actor,
        Outcome outcome,
        Severity severity,
        String summary,
        String resourceType,
        String resourceId,
        UUID customerUserId,
        UUID paymentId,
        UUID correlationId,
        Map<String, Object> payload) {

    public enum Outcome { SUCCESS, FAILURE, REJECTED }

    public enum Severity { INFO, WARN, ERROR, CRITICAL }

    public static Builder builder(String type) {
        return new Builder(type);
    }

    public static final class Builder {
        private final String type;
        private Instant occurredAt = Instant.now();
        private String actor = "system";
        private Outcome outcome = Outcome.SUCCESS;
        private Severity severity = Severity.INFO;
        private String summary;
        private String resourceType;
        private String resourceId;
        private UUID customerUserId;
        private UUID paymentId;
        private UUID correlationId;
        private Map<String, Object> payload;

        private Builder(String type) { this.type = type; }

        public Builder occurredAt(Instant v) { this.occurredAt = v; return this; }
        public Builder actor(String v) { this.actor = v; return this; }
        public Builder outcome(Outcome v) { this.outcome = v; return this; }
        public Builder severity(Severity v) { this.severity = v; return this; }
        public Builder summary(String v) { this.summary = v; return this; }
        public Builder resourceType(String v) { this.resourceType = v; return this; }
        public Builder resourceId(String v) { this.resourceId = v; return this; }
        public Builder customerUserId(UUID v) { this.customerUserId = v; return this; }
        public Builder paymentId(UUID v) { this.paymentId = v; return this; }
        public Builder correlationId(UUID v) { this.correlationId = v; return this; }
        public Builder payload(Map<String, Object> v) { this.payload = v; return this; }

        public CustomerAuditEvent build() {
            return new CustomerAuditEvent(type, occurredAt, actor, outcome, severity, summary,
                    resourceType, resourceId, customerUserId, paymentId, correlationId, payload);
        }
    }
}
