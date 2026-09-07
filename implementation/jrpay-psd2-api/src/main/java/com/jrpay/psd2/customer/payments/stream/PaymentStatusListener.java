package com.jrpay.psd2.customer.payments.stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jrpay.psd2.customer.domain.CustomerPaymentEntity;
import com.jrpay.psd2.customer.domain.CustomerPaymentRepository;
import com.jrpay.psd2.customer.domain.ProcessedKafkaEvent;
import com.jrpay.psd2.customer.domain.ProcessedKafkaEventRepository;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Consumes {@code payment.status-updates} and:
 * <ol>
 *   <li>updates the {@link CustomerPaymentEntity} status,
 *   <li>fans out a typed SSE event to live subscribers.
 * </ol>
 *
 * <p>Idempotent via {@code processed_kafka_events}.
 */
@Component
public class PaymentStatusListener {

    private static final Logger log = LoggerFactory.getLogger(PaymentStatusListener.class);

    private final CustomerPaymentRepository payments;
    private final ProcessedKafkaEventRepository processed;
    private final PaymentStreamHub hub;
    private final ObjectMapper mapper;

    public PaymentStatusListener(CustomerPaymentRepository payments,
                                 ProcessedKafkaEventRepository processed,
                                 PaymentStreamHub hub,
                                 ObjectMapper mapper) {
        this.payments = payments;
        this.processed = processed;
        this.hub = hub;
        this.mapper = mapper;
    }

    @KafkaListener(
            topics = "${jrpay.psd2.kafka.payment-status-topic:payment.status-updates}",
            groupId = "${jrpay.psd2.kafka.group-id:jrpay-psd2-api}",
            containerFactory = "kafkaListenerContainerFactory")
    @Transactional
    public void onStatusUpdate(@Payload String value,
                               @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
                               @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
                               @Header(KafkaHeaders.OFFSET) long offset,
                               Acknowledgment ack) {
        ProcessedKafkaEvent.Pk pk = new ProcessedKafkaEvent.Pk(topic, partition, offset);
        if (processed.existsById(pk)) {
            ack.acknowledge();
            return;
        }
        try {
            JsonNode event = mapper.readTree(value);
            String paymentId = event.path("paymentId").asText(null);
            String newStatus = event.path("status").asText(null);
            if (paymentId == null || newStatus == null) {
                ack.acknowledge();
                return;
            }
            payments.findById(paymentId).ifPresent(p -> {
                p.setStatus(newStatus);
                if ("SETTLED".equals(newStatus)) p.setSettledAt(Instant.now());
                if ("REJECTED".equals(newStatus)) {
                    String reason = event.path("rejectionReason").asText(null);
                    if (reason != null) p.setRejectionReason(reason);
                }
                payments.save(p);
                fan(p, newStatus, event);
            });
            processed.save(new ProcessedKafkaEvent(topic, partition, offset));
            ack.acknowledge();
        } catch (Exception e) {
            log.warn("Failed to project payment.status-updates at {}/{} offset {}: {}",
                    topic, partition, offset, e.getMessage());
            throw new RuntimeException(e);
        }
    }

    private void fan(CustomerPaymentEntity p, String newStatus, JsonNode event) {
        UUID uid = p.getCustomerUserId();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("paymentId", p.getPaymentId());
        payload.put("status", newStatus);
        payload.put("amount", p.getAmount().toPlainString());
        payload.put("currency", p.getCurrency());
        payload.put("at", Instant.now().toString());
        String eventName = switch (newStatus) {
            case "SETTLED" -> "payment.settled";
            case "REJECTED" -> "payment.rejected";
            case "RETURNED" -> "payment.returned";
            case "HELD" -> "payment.held";
            default -> "payment.status-updated";
        };
        hub.publish(uid, p.getPaymentId(), eventName, payload);
    }
}
