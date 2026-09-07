package com.jrpay.fraud.casework.projection;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jrpay.fraud.casework.domain.ProcessedKafkaEvent;
import com.jrpay.fraud.casework.domain.ProcessedKafkaEventRepository;
import com.jrpay.fraud.casework.domain.SanctionsHitEntity;
import com.jrpay.fraud.casework.domain.SanctionsHitRepository;
import com.jrpay.fraud.casework.stream.CaseworkStreamHub;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Bridges {@code sanctions.hits} into the projection.
 */
@Component
public class SanctionsHitProjector {

    private static final Logger log = LoggerFactory.getLogger(SanctionsHitProjector.class);

    private final SanctionsHitRepository hits;
    private final ProcessedKafkaEventRepository processed;
    private final CaseworkStreamHub hub;
    private final ObjectMapper mapper;
    private final Duration slaWindow;

    public SanctionsHitProjector(SanctionsHitRepository hits,
                                 ProcessedKafkaEventRepository processed,
                                 CaseworkStreamHub hub,
                                 ObjectMapper mapper,
                                 @Value("${jrpay.fraud.sanctions.sla:PT2H}") Duration slaWindow) {
        this.hits = hits;
        this.processed = processed;
        this.hub = hub;
        this.mapper = mapper;
        this.slaWindow = slaWindow;
    }

    @KafkaListener(
            topics = "${jrpay.fraud.kafka.sanctions-topic:sanctions.hits}",
            groupId = "${jrpay.fraud.kafka.group-id:jrpay-fraud-detection}",
            containerFactory = "caseworkListenerContainerFactory")
    @Transactional
    public void onSanctionsHit(@Payload String value,
                               @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
                               @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
                               @Header(KafkaHeaders.OFFSET) long offset,
                               Acknowledgment ack) {
        ProcessedKafkaEvent.Pk pk = new ProcessedKafkaEvent.Pk(topic, partition, offset);
        if (processed.existsById(pk)) { ack.acknowledge(); return; }
        try {
            JsonNode event = mapper.readTree(value);
            UUID hitId = parseUuid(event.path("hitId").asText(null));
            if (hitId == null) hitId = UUID.randomUUID();
            if (hits.existsById(hitId)) {
                processed.save(new ProcessedKafkaEvent(topic, partition, offset));
                ack.acknowledge();
                return;
            }
            UUID paymentId = parseUuid(event.path("paymentId").asText());
            if (paymentId == null) {
                processed.save(new ProcessedKafkaEvent(topic, partition, offset));
                ack.acknowledge();
                return;
            }
            Instant initiatedAt = parseInstant(event.path("initiatedAt").asText(null));
            if (initiatedAt == null) initiatedAt = Instant.now();
            String matchedPayloadJson;
            try {
                matchedPayloadJson = mapper.writeValueAsString(event.path("matchedEntity"));
            } catch (Exception e) { matchedPayloadJson = null; }

            SanctionsHitEntity entity = new SanctionsHitEntity(
                    hitId, paymentId,
                    parseUuid(event.path("customerUserId").asText(null)),
                    event.path("debtorAccountId").asText(""),
                    event.path("matchedEntityName").asText(""),
                    event.path("matchedListId").asText(""),
                    parseDecimal(event.path("matchScore").asText("0")),
                    event.path("creditorIban").asText(""),
                    event.path("creditorName").asText(""),
                    parseDecimal(event.path("amount").asText("0")),
                    event.path("currency").asText("NOK"),
                    event.path("reference").asText(null),
                    matchedPayloadJson,
                    initiatedAt,
                    initiatedAt.plus(slaWindow));
            hits.save(entity);

            Map<String, Object> sse = new LinkedHashMap<>();
            sse.put("hitId", hitId.toString());
            sse.put("paymentId", paymentId.toString());
            sse.put("matchedListId", entity.getMatchedListId());
            sse.put("matchedEntity", entity.getMatchedEntityName());
            hub.publish(CaseworkStreamHub.Scope.SANCTIONS,
                    hitId.toString(), "hit.created", sse);
            processed.save(new ProcessedKafkaEvent(topic, partition, offset));
            ack.acknowledge();
        } catch (Exception e) {
            log.warn("Failed to project sanctions hit at {}/{} offset {}: {}",
                    topic, partition, offset, e.getMessage());
            throw new RuntimeException(e);
        }
    }

    private static UUID parseUuid(String s) {
        if (s == null || s.isBlank()) return null;
        try { return UUID.fromString(s); } catch (Exception e) { return null; }
    }

    private static BigDecimal parseDecimal(String s) {
        if (s == null || s.isBlank()) return BigDecimal.ZERO;
        try { return new BigDecimal(s); } catch (Exception e) { return BigDecimal.ZERO; }
    }

    private static Instant parseInstant(String s) {
        if (s == null || s.isBlank()) return null;
        try { return Instant.parse(s); } catch (Exception e) { return null; }
    }
}
