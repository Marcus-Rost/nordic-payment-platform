package com.jrpay.notifications.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jrpay.notifications.adapter.NotificationDispatcher;
import com.jrpay.notifications.domain.Audience;
import com.jrpay.notifications.domain.Notification;
import com.jrpay.notifications.domain.NotificationCategory;
import com.jrpay.notifications.domain.ProcessedKafkaEvent;
import com.jrpay.notifications.inbox.Ulid;
import com.jrpay.notifications.repository.ProcessedKafkaEventRepository;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Consumes {@code payment.status-updates} (per A2 / A3 contract) and
 * projects HELD / REJECTED / SETTLED / RETURNED into the customer's inbox.
 *
 * <p>Idempotency: every record's {@code (topic, partition, offset)} is
 * inserted into {@code processed_kafka_events} before processing. A
 * duplicate hits the unique constraint and we ack-skip. The event payload
 * supplies {@code customerUserId} (set by A4 / billpay when emitting the
 * status update — see ARCHITECTURE_DECISIONS G.5b); records without it are
 * skipped (operator-side internal events).
 */
@Component
public class PaymentStatusUpdatesListener {

    private static final Logger log = LoggerFactory.getLogger(PaymentStatusUpdatesListener.class);

    private final ProcessedKafkaEventRepository processedEvents;
    private final NotificationDispatcher dispatcher;
    private final ObjectMapper mapper;

    public PaymentStatusUpdatesListener(ProcessedKafkaEventRepository processedEvents,
                                        NotificationDispatcher dispatcher,
                                        ObjectMapper mapper) {
        this.processedEvents = processedEvents;
        this.dispatcher = dispatcher;
        this.mapper = mapper;
    }

    @KafkaListener(
            topics = "${jrpay.notifications.kafka.topics.payment-status-updates}",
            containerFactory = "kafkaListenerContainerFactory")
    @Transactional
    public void onMessage(ConsumerRecord<String, String> record, Acknowledgment ack) {
        try {
            // Best-effort idempotency: insert the marker first; constraint
            // violation = already processed.
            try {
                processedEvents.saveAndFlush(new ProcessedKafkaEvent(
                        record.topic(), record.partition(), record.offset()));
            } catch (DataIntegrityViolationException duplicate) {
                ack.acknowledge();
                return;
            }

            JsonNode payload = mapper.readTree(record.value() == null ? "{}" : record.value());
            String customerUserIdStr = textOrNull(payload, "customerUserId");
            if (customerUserIdStr == null) {
                ack.acknowledge();
                return;
            }
            UUID userId;
            try {
                userId = UUID.fromString(customerUserIdStr);
            } catch (IllegalArgumentException e) {
                log.warn("Skipping payment-status-update with non-UUID customerUserId={}", customerUserIdStr);
                ack.acknowledge();
                return;
            }

            String status = textOrNull(payload, "status");
            String paymentId = textOrNull(payload, "paymentId");
            if (status == null || paymentId == null) {
                ack.acknowledge();
                return;
            }

            Notification.Builder b = Notification.builder()
                    .notificationId(Ulid.generate())
                    .userId(userId)
                    .audience(Audience.customer)
                    .relatedPaymentId(safeUuid(paymentId))
                    .params(buildParams(payload));

            switch (status) {
                case "HELD" -> {
                    b.category(NotificationCategory.FRAUD_HOLD)
                            .priority(Notification.Priority.warn)
                            .titleKey("notification.payment.held.title")
                            .bodySummaryKey("notification.payment.held.body")
                            .deepLink("jrpay://payment/" + paymentId)
                            .actionHint(Notification.ActionHint.VIEW)
                            .supportReference(textOrNull(payload, "caseRef"));
                }
                case "REJECTED" -> {
                    String reason = textOrNull(payload, "rejectionReason");
                    boolean sanctions = "SANCTIONS_HIT".equals(reason)
                            || "FRAUD_CONFIRMED".equals(reason);
                    b.category(sanctions
                                    ? NotificationCategory.SANCTIONS_BLOCK
                                    : NotificationCategory.PAYMENT_STATUS)
                            .priority(Notification.Priority.warn)
                            .titleKey(sanctions
                                    ? "notification.payment.regulatory-block.title"
                                    : "notification.payment.rejected.title")
                            .bodySummaryKey(sanctions
                                    ? "notification.payment.regulatory-block.body"
                                    : "notification.payment.rejected.body")
                            .deepLink("jrpay://payment/" + paymentId);
                }
                case "SETTLED" -> {
                    b.category(NotificationCategory.PAYMENT_STATUS)
                            .priority(Notification.Priority.info)
                            .titleKey("notification.payment.settled.title")
                            .bodySummaryKey("notification.payment.settled.body")
                            .deepLink("jrpay://payment/" + paymentId);
                }
                case "RETURNED" -> {
                    b.category(NotificationCategory.PAYMENT_STATUS)
                            .priority(Notification.Priority.warn)
                            .titleKey("notification.payment.returned.title")
                            .bodySummaryKey("notification.payment.returned.body")
                            .deepLink("jrpay://payment/" + paymentId);
                }
                default -> {
                    // Non-final intermediate status — no inbox write.
                    ack.acknowledge();
                    return;
                }
            }

            dispatcher.dispatch(b.build());
            ack.acknowledge();
        } catch (Exception e) {
            // Don't ack on unexpected errors — let Kafka redeliver.
            log.error("Failed to project payment-status-update at offset {}: {}",
                    record.offset(), e.toString(), e);
        }
    }

    private static String textOrNull(JsonNode node, String name) {
        JsonNode field = node.get(name);
        return field == null || field.isNull() ? null : field.asText();
    }

    private static UUID safeUuid(String value) {
        try { return UUID.fromString(value); } catch (Exception e) { return null; }
    }

    private static Map<String, Object> buildParams(JsonNode payload) {
        Map<String, Object> params = new LinkedHashMap<>();
        for (var copy : new String[]{"amount", "currency", "creditorName", "rejectionReason"}) {
            String value = textOrNull(payload, copy);
            if (value != null) params.put(copy, value);
        }
        return params;
    }
}
