package com.jrpay.ledger.admin.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes admin-action audit events to the {@code audit.events} topic
 * consumed by {@code jrpay-audit-query}. The payload schema matches what
 * {@code AuditIngestService} parses: {@code eventId}, {@code occurredAt},
 * {@code type}, {@code actor}, {@code actorKind}, {@code outcome},
 * {@code severity}, {@code summary}, {@code resourceType},
 * {@code resourceId}, {@code customerUserId}, {@code operatorUserId},
 * plus a free-form {@code payload} sub-document for the action-specific
 * details.
 *
 * <p>Per the architecture spec (ARCH §7.7), admin PII reveals are also
 * published to the {@code audit.pii-revealed} topic for a 5-year-retention
 * forensic stream. The publisher emits both for the reveal flow.
 */
@Component
public class AdminAuditPublisher {

    private static final Logger log = LoggerFactory.getLogger(AdminAuditPublisher.class);
    private static final String AUDIT_EVENTS_TOPIC = "audit.events";
    private static final String PII_REVEALED_TOPIC = "audit.pii-revealed";
    private static final char[] CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper mapper;
    private final SecureRandom random = new SecureRandom();
    private final boolean publishPiiToSeparateTopic;

    public AdminAuditPublisher(KafkaTemplate<String, String> kafkaTemplate,
                               ObjectMapper mapper,
                               @Value("${jrpay.ledger.audit.pii-separate-topic:true}") boolean publishPiiToSeparateTopic) {
        this.kafkaTemplate = kafkaTemplate;
        this.mapper = mapper;
        this.publishPiiToSeparateTopic = publishPiiToSeparateTopic;
    }

    /**
     * Publish an audit event. Returns the {@code eventId} (ULID-shaped) so
     * the caller can persist a foreign-key reference to the event.
     */
    public String publish(AdminAuditEvent event) {
        String eventId = ulid();
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("eventId", eventId);
        json.put("occurredAt", event.occurredAt().toString());
        json.put("type", event.type());
        json.put("actor", event.actor());
        json.put("actorKind", "operator");
        json.put("outcome", event.outcome().name());
        json.put("severity", event.severity().name());
        if (event.summary() != null) json.put("summary", event.summary());
        if (event.resourceType() != null) json.put("resourceType", event.resourceType());
        if (event.resourceId() != null) json.put("resourceId", event.resourceId());
        if (event.operatorUserId() != null) json.put("operatorUserId", event.operatorUserId().toString());
        if (event.customerUserId() != null) json.put("customerUserId", event.customerUserId().toString());
        if (event.paymentId() != null) json.put("paymentId", event.paymentId().toString());
        if (event.correlationId() != null) json.put("correlationId", event.correlationId().toString());
        if (event.payload() != null && !event.payload().isEmpty()) json.put("payload", event.payload());

        String body;
        try {
            body = mapper.writeValueAsString(json);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialise audit event: " + event.type(), e);
        }
        String key = event.resourceId() == null ? eventId : event.resourceId();
        send(AUDIT_EVENTS_TOPIC, key, body, event.type(), eventId);

        if (publishPiiToSeparateTopic && event.type().startsWith("audit.pii-revealed")) {
            String piiKey = event.operatorUserId() == null ? eventId : event.operatorUserId().toString();
            send(PII_REVEALED_TOPIC, piiKey, body, event.type(), eventId);
        }
        return eventId;
    }

    private void send(String topic, String key, String body, String type, String eventId) {
        kafkaTemplate.send(topic, key, body)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to publish {}/{} (eventId={}): {}",
                                topic, type, eventId, ex.getMessage());
                    } else {
                        log.debug("Published {}/{} eventId={} partition={} offset={}",
                                topic, type, eventId,
                                result.getRecordMetadata().partition(),
                                result.getRecordMetadata().offset());
                    }
                });
    }

    private String ulid() {
        long ts = System.currentTimeMillis();
        StringBuilder sb = new StringBuilder(26);
        for (int i = 9; i >= 0; i--) {
            sb.insert(0, CROCKFORD[(int) (ts & 0x1F)]);
            ts >>>= 5;
        }
        for (int i = 0; i < 16; i++) {
            sb.append(CROCKFORD[random.nextInt(CROCKFORD.length)]);
        }
        return sb.toString();
    }
}
