package com.jrpay.fraud.casework.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class AdminAuditPublisher {

    private static final Logger log = LoggerFactory.getLogger(AdminAuditPublisher.class);
    private static final String TOPIC = "audit.events";
    private static final char[] CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper mapper;
    private final SecureRandom random = new SecureRandom();

    public AdminAuditPublisher(KafkaTemplate<String, String> kafkaTemplate, ObjectMapper mapper) {
        this.kafkaTemplate = kafkaTemplate;
        this.mapper = mapper;
    }

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
        if (event.caseId() != null) json.put("caseId", event.caseId().toString());
        if (event.correlationId() != null) json.put("correlationId", event.correlationId().toString());
        if (event.payload() != null && !event.payload().isEmpty()) json.put("payload", event.payload());

        String body;
        try {
            body = mapper.writeValueAsString(json);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialise audit event: " + event.type(), e);
        }
        String key = event.resourceId() == null ? eventId : event.resourceId();
        kafkaTemplate.send(TOPIC, key, body)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to publish {} (eventId={}): {}",
                                event.type(), eventId, ex.getMessage());
                    }
                });
        return eventId;
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
