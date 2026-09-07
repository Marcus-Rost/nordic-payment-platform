package com.jrpay.ledger.command;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jrpay.ledger.event.LedgerEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes ledger events to the ledger.events Kafka topic.
 * This is the Kafka-first principle: events are the source of truth.
 */
@Component
public class KafkaEventPublisher implements EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(KafkaEventPublisher.class);
    private static final String TOPIC = "ledger.events";

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public KafkaEventPublisher(KafkaTemplate<String, String> kafkaTemplate,
                               ObjectMapper objectMapper) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public void publish(LedgerEvent event) {
        try {
            String payload = objectMapper.writeValueAsString(event);
            String key = event.accountId();

            kafkaTemplate.send(TOPIC, key, payload)
                    .whenComplete((result, ex) -> {
                        if (ex != null) {
                            log.error("Failed to publish event {} to {}: {}",
                                    event.eventType(), TOPIC, ex.getMessage());
                        } else {
                            log.debug("Published {} to {} partition {} offset {}",
                                    event.eventType(), TOPIC,
                                    result.getRecordMetadata().partition(),
                                    result.getRecordMetadata().offset());
                        }
                    });
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize event: " + event.eventType(), e);
        }
    }
}
