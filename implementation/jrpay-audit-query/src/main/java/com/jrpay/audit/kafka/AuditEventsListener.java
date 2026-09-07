package com.jrpay.audit.kafka;

import com.jrpay.audit.ingest.AuditIngestService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * Bridges {@code audit.events} → {@link AuditIngestService}. The ingest
 * service handles its own transaction + idempotency check; this listener
 * just unwraps the record, calls into it, and acks once the call returns.
 *
 * <p>Failures during ingestion are NOT acked so Kafka redelivers; only the
 * idempotent {@code DUPLICATE} short-circuit and the successful
 * {@code PERSISTED} path advance the consumer offset.
 */
@Component
public class AuditEventsListener {

    private static final Logger log = LoggerFactory.getLogger(AuditEventsListener.class);

    private final AuditIngestService ingestService;

    public AuditEventsListener(AuditIngestService ingestService) {
        this.ingestService = ingestService;
    }

    @KafkaListener(
            topics = "${jrpay.audit.kafka.topic}",
            containerFactory = "kafkaListenerContainerFactory")
    public void onMessage(ConsumerRecord<String, String> record, Acknowledgment ack) {
        try {
            AuditIngestService.IngestRecord ingest = new AuditIngestService.IngestRecord(
                    record.topic(), record.partition(), record.offset(), record.value());
            AuditIngestService.IngestResult result = ingestService.ingest(ingest);
            ack.acknowledge();
            if (result == AuditIngestService.IngestResult.DUPLICATE) {
                log.debug("Skipped duplicate audit event topic={} partition={} offset={}",
                        record.topic(), record.partition(), record.offset());
            }
        } catch (Exception e) {
            log.error("Failed to ingest audit event at offset {}: {}",
                    record.offset(), e.toString(), e);
            // No ack — Kafka will redeliver.
        }
    }
}
