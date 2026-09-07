package com.jrpay.starter.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Programmatic audit logging API. Publishes structured audit events
 * to both the local log (synchronous, compliance-primary) and
 * Kafka audit.events topic (async, when Kafka module is on classpath).
 *
 * <p>Usage: {@code auditLogger.log("PAYMENT_SETTLED", Map.of("transactionId", txId));}
 */
public class AuditLogger {

    private static final Logger auditLog = LoggerFactory.getLogger("com.jrpay.audit");

    private final String serviceName;
    private final AuditEventPublisher publisher;

    public AuditLogger(String serviceName, AuditEventPublisher publisher) {
        this.serviceName = Objects.requireNonNull(serviceName);
        this.publisher = publisher;
    }

    /**
     * Log an audit event.
     *
     * @param eventType  the event type (e.g., "PAYMENT_SETTLED", "ACCOUNT_FROZEN")
     * @param properties additional event properties (key-value pairs)
     */
    public void log(String eventType, Map<String, Object> properties) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("eventType", eventType);
        event.put("service", serviceName);
        event.put("timestamp", Instant.now().toString());
        if (properties != null) {
            event.putAll(properties);
        }

        // Primary: local structured log (synchronous, always succeeds if disk available)
        auditLog.info("{}", event);

        // Secondary: publish to Kafka (async, best-effort)
        if (publisher != null) {
            publisher.publish(event);
        }
    }

    /**
     * Log an audit event with no additional properties.
     */
    public void log(String eventType) {
        log(eventType, null);
    }
}
