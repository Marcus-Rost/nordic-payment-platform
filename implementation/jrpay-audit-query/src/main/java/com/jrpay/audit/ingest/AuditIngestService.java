package com.jrpay.audit.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jrpay.audit.chain.ChainHasher;
import com.jrpay.audit.domain.AuditEvent;
import com.jrpay.audit.domain.ProcessedKafkaEvent;
import com.jrpay.audit.repository.AuditEventRepository;
import com.jrpay.audit.repository.ProcessedKafkaEventRepository;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists incoming audit events with their chain-hash. The Kafka listener
 * delegates here so the ingest logic can be unit-tested directly.
 */
@Service
public class AuditIngestService {

    private static final Logger log = LoggerFactory.getLogger(AuditIngestService.class);

    private final AuditEventRepository events;
    private final ProcessedKafkaEventRepository processed;
    private final ChainHasher hasher;
    private final ObjectMapper mapper;

    public AuditIngestService(AuditEventRepository events,
                              ProcessedKafkaEventRepository processed,
                              ChainHasher hasher,
                              ObjectMapper mapper) {
        this.events = events;
        this.processed = processed;
        this.hasher = hasher;
        this.mapper = mapper;
    }

    /**
     * Idempotent ingest. Returns true if the event was newly persisted,
     * false if the (topic, partition, offset) was already seen.
     */
    @Transactional
    public IngestResult ingest(IngestRecord record) {
        ProcessedKafkaEvent.Pk pk = new ProcessedKafkaEvent.Pk(
                record.topic(), record.partition(), record.kafkaOffset());
        if (processed.existsById(pk)) {
            return IngestResult.DUPLICATE;
        }
        // Race window: if a parallel consumer wins, the unique-constraint
        // violation will surface as DataIntegrityViolationException and
        // bubble out of the transaction. The listener does not ack on
        // failure → Kafka redelivers, the existsById check then short-
        // circuits.
        processed.save(new ProcessedKafkaEvent(
                record.topic(), record.partition(), record.kafkaOffset()));

        JsonNode payload;
        try {
            payload = record.value() == null || record.value().isBlank()
                    ? mapper.createObjectNode()
                    : mapper.readTree(record.value());
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid JSON in audit event payload", e);
        }

        String eventId = textOrUuid(payload, "eventId");
        OffsetDateTime occurredAt = parseInstant(textOrNull(payload, "occurredAt"));
        if (occurredAt == null) occurredAt = OffsetDateTime.now();

        Map<String, Object> payloadMap = mapper.convertValue(payload, Map.class);

        Long maxOffset = events.findMaxChainOffset().orElse(null);
        long nextOffset = maxOffset == null ? 1L : maxOffset + 1;
        String priorHash = null;
        if (maxOffset != null) {
            priorHash = events.findByChainOffset(maxOffset)
                    .map(AuditEvent::getChainHash).orElse(null);
        }
        String chainHash = hasher.hash(payloadMap, priorHash, nextOffset);

        AuditEvent.Builder b = AuditEvent.builder()
                .eventId(eventId)
                .occurredAt(occurredAt)
                .type(textOrDefault(payload, "type", "unknown"))
                .actor(textOrDefault(payload, "actor", "system:unknown"))
                .actorKind(parseActorKind(textOrNull(payload, "actorKind")))
                .outcome(parseOutcome(textOrNull(payload, "outcome")))
                .severity(parseSeverity(textOrNull(payload, "severity")))
                .summary(textOrNull(payload, "summary"))
                .resourceType(textOrNull(payload, "resourceType"))
                .resourceId(textOrNull(payload, "resourceId"))
                .correlationId(uuidOrNull(textOrNull(payload, "correlationId")))
                .customerUserId(uuidOrNull(textOrNull(payload, "customerUserId")))
                .operatorUserId(uuidOrNull(textOrNull(payload, "operatorUserId")))
                .consentId(uuidOrNull(textOrNull(payload, "consentId")))
                .paymentId(uuidOrNull(textOrNull(payload, "paymentId")))
                .caseId(uuidOrNull(textOrNull(payload, "caseId")))
                .payload(payloadMap)
                .priorHash(priorHash)
                .chainHash(chainHash)
                .kafkaTopic(record.topic())
                .kafkaPartition(record.partition())
                .kafkaOffset(record.kafkaOffset());

        AuditEvent saved = b.build();
        events.save(saved);
        log.debug("ingested audit event id={} type={} chainOffset={}",
                eventId, saved.getType(), nextOffset);
        return IngestResult.PERSISTED;
    }

    private static String textOrNull(JsonNode node, String name) {
        JsonNode field = node.get(name);
        return field == null || field.isNull() ? null : field.asText();
    }

    private static String textOrDefault(JsonNode node, String name, String fallback) {
        String v = textOrNull(node, name);
        return v == null ? fallback : v;
    }

    private static String textOrUuid(JsonNode node, String name) {
        String v = textOrNull(node, name);
        return v != null ? v : ulidLike();
    }

    private static String ulidLike() {
        // Minimal monotonic id when the producer didn't supply one. This
        // mirrors notification-service's Ulid generator format (26 chars).
        // Static here to keep the audit module self-contained.
        long ts = System.currentTimeMillis();
        StringBuilder sb = new StringBuilder(26);
        char[] alphabet = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
        for (int i = 9; i >= 0; i--) {
            sb.insert(0, alphabet[(int) (ts & 0x1F)]);
            ts >>>= 5;
        }
        java.security.SecureRandom r = new java.security.SecureRandom();
        for (int i = 0; i < 16; i++) {
            sb.append(alphabet[r.nextInt(alphabet.length)]);
        }
        return sb.toString();
    }

    private static OffsetDateTime parseInstant(String value) {
        if (value == null) return null;
        try {
            return OffsetDateTime.parse(value);
        } catch (Exception e) {
            return null;
        }
    }

    private static UUID uuidOrNull(String value) {
        if (value == null) return null;
        try {
            return UUID.fromString(value);
        } catch (Exception e) {
            return null;
        }
    }

    private static AuditEvent.ActorKind parseActorKind(String v) {
        if (v == null) return AuditEvent.ActorKind.system;
        try { return AuditEvent.ActorKind.valueOf(v); }
        catch (Exception e) { return AuditEvent.ActorKind.system; }
    }

    private static AuditEvent.Outcome parseOutcome(String v) {
        if (v == null) return AuditEvent.Outcome.SUCCESS;
        try { return AuditEvent.Outcome.valueOf(v); }
        catch (Exception e) { return AuditEvent.Outcome.SUCCESS; }
    }

    private static AuditEvent.Severity parseSeverity(String v) {
        if (v == null) return AuditEvent.Severity.INFO;
        try { return AuditEvent.Severity.valueOf(v); }
        catch (Exception e) { return AuditEvent.Severity.INFO; }
    }

    public record IngestRecord(String topic, int partition, long kafkaOffset, String value) { }

    public enum IngestResult { PERSISTED, DUPLICATE }
}
