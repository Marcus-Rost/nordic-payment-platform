package com.jrpay.fraud.casework.projection;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jrpay.fraud.casework.domain.FraudCaseEntity;
import com.jrpay.fraud.casework.domain.FraudCaseRepository;
import com.jrpay.fraud.casework.domain.FraudHeldPaymentEntity;
import com.jrpay.fraud.casework.domain.FraudHeldPaymentRepository;
import com.jrpay.fraud.casework.domain.FraudTimelineEntry;
import com.jrpay.fraud.casework.domain.FraudTimelineRepository;
import com.jrpay.fraud.casework.domain.ProcessedKafkaEvent;
import com.jrpay.fraud.casework.domain.ProcessedKafkaEventRepository;
import com.jrpay.fraud.casework.stream.CaseworkStreamHub;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
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
 * Bridges {@code payment.fraud-check-response} into the casework projection.
 * Cases are created when {@code decision == HOLD} (or when status indicates
 * a HELD payment); existing cases get timeline entries on subsequent updates.
 */
@Component
public class FraudCaseProjector {

    private static final Logger log = LoggerFactory.getLogger(FraudCaseProjector.class);

    private final FraudCaseRepository cases;
    private final FraudTimelineRepository timeline;
    private final FraudHeldPaymentRepository held;
    private final ProcessedKafkaEventRepository processed;
    private final CaseworkStreamHub hub;
    private final ObjectMapper mapper;
    private final Duration slaWindow;

    public FraudCaseProjector(FraudCaseRepository cases,
                              FraudTimelineRepository timeline,
                              FraudHeldPaymentRepository held,
                              ProcessedKafkaEventRepository processed,
                              CaseworkStreamHub hub,
                              ObjectMapper mapper,
                              @Value("${jrpay.fraud.case.sla:PT4H}") Duration slaWindow) {
        this.cases = cases;
        this.timeline = timeline;
        this.held = held;
        this.processed = processed;
        this.hub = hub;
        this.mapper = mapper;
        this.slaWindow = slaWindow;
    }

    @KafkaListener(
            topics = "${jrpay.fraud.kafka.fraud-check-response-topic:payment.fraud-check-response}",
            groupId = "${jrpay.fraud.kafka.group-id:jrpay-fraud-detection}",
            containerFactory = "caseworkListenerContainerFactory")
    @Transactional
    public void onFraudCheckResponse(@Payload String value,
                                     @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
                                     @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
                                     @Header(KafkaHeaders.OFFSET) long offset,
                                     Acknowledgment ack) {
        ProcessedKafkaEvent.Pk pk = new ProcessedKafkaEvent.Pk(topic, partition, offset);
        if (processed.existsById(pk)) { ack.acknowledge(); return; }
        try {
            JsonNode event = mapper.readTree(value);
            String decision = event.path("decision").asText("");
            if (!"HOLD".equals(decision)) {
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
            FraudCaseEntity existing = cases.findByPaymentId(paymentId).orElse(null);
            if (existing != null) {
                existing.touch();
                cases.save(existing);
                processed.save(new ProcessedKafkaEvent(topic, partition, offset));
                ack.acknowledge();
                return;
            }
            FraudCaseEntity created = createFromEvent(event, paymentId);
            cases.save(created);
            timeline.save(new FraudTimelineEntry(
                    created.getCaseId(), FraudTimelineEntry.Type.CASE_CREATED,
                    "system", "Score " + created.getScore().toPlainString()
                            + " (" + created.getScoreBand() + ")"));
            // Track the held payment that drove the case.
            held.save(new FraudHeldPaymentEntity(
                    created.getCaseId(), paymentId,
                    created.getAmount(), created.getCurrency(), "HELD"));
            // Fan out to live subscribers.
            Map<String, Object> sse = new LinkedHashMap<>();
            sse.put("caseId", created.getCaseId().toString());
            sse.put("paymentId", paymentId.toString());
            sse.put("score", created.getScore().toPlainString());
            sse.put("scoreBand", created.getScoreBand().name());
            hub.publish(CaseworkStreamHub.Scope.FRAUD,
                    created.getCaseId().toString(), "case.created", sse);
            processed.save(new ProcessedKafkaEvent(topic, partition, offset));
            ack.acknowledge();
        } catch (Exception e) {
            log.warn("Failed to project fraud-check-response at {}/{} offset {}: {}",
                    topic, partition, offset, e.getMessage());
            throw new RuntimeException(e);
        }
    }

    private FraudCaseEntity createFromEvent(JsonNode event, UUID paymentId) {
        UUID caseId = UUID.randomUUID();
        BigDecimal score = parseDecimal(event.path("score").asText("0"));
        BigDecimal threshold = parseDecimal(event.path("threshold").asText("0.6"));
        String triggeredRules = collectRules(event.path("triggeredRules"));
        Map<String, Object> breakdown = mapper.convertValue(event.path("breakdown"), Map.class);
        String breakdownJson;
        try {
            breakdownJson = breakdown == null ? null : mapper.writeValueAsString(breakdown);
        } catch (Exception e) { breakdownJson = null; }

        Instant initiatedAt = parseInstant(event.path("initiatedAt").asText(null));
        if (initiatedAt == null) initiatedAt = Instant.now();
        Instant slaDeadline = initiatedAt.plus(slaWindow);

        return new FraudCaseEntity(
                caseId, paymentId,
                parseUuid(event.path("customerUserId").asText(null)),
                event.path("debtorAccountId").asText(""),
                event.path("holderName").asText(null),
                event.path("creditorIban").asText(""),
                event.path("creditorName").asText(null),
                parseDecimal(event.path("amount").asText("0")),
                event.path("currency").asText("NOK"),
                event.path("reference").asText(null),
                event.path("executionMode").asText("INSTANT"),
                score, threshold, triggeredRules, breakdownJson,
                initiatedAt, slaDeadline);
    }

    private static String collectRules(JsonNode rulesNode) {
        if (rulesNode == null || rulesNode.isMissingNode() || !rulesNode.isArray()) return null;
        StringBuilder sb = new StringBuilder();
        Iterator<JsonNode> it = rulesNode.elements();
        boolean first = true;
        while (it.hasNext()) {
            JsonNode n = it.next();
            String s = n.isTextual() ? n.asText() : n.path("ruleId").asText(null);
            if (s == null || s.isBlank()) continue;
            if (!first) sb.append(',');
            sb.append(s);
            first = false;
        }
        return sb.length() == 0 ? null : sb.toString();
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
