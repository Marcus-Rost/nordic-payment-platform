package com.jrpay.billpay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jrpay.billpay.domain.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Kafka consumer for payment status updates from A2.
 * Updates R2P request status based on clearing/settlement outcome.
 */
@Component
public class StatusUpdateKafkaConsumer {

    private static final Logger log = LoggerFactory.getLogger(StatusUpdateKafkaConsumer.class);

    private final PaymentRequestRepository requestRepository;
    private final ObjectMapper objectMapper;

    public StatusUpdateKafkaConsumer(PaymentRequestRepository requestRepository,
                                    ObjectMapper objectMapper) {
        this.requestRepository = requestRepository;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(
            topics = "payment.status-updates",
            groupId = "a3-status-consumer"
    )
    public void handleStatusUpdate(String message) {
        try {
            JsonNode json = objectMapper.readTree(message);
            String transactionId = json.path("transactionId").asText();
            String status = json.path("status").asText();

            // Find the payment request by transaction ID
            var requests = requestRepository.findAll().stream()
                    .filter(r -> transactionId.equals(r.getTransactionId()))
                    .toList();

            if (requests.isEmpty()) {
                log.debug("No payment request found for transaction {}", transactionId);
                return;
            }

            PaymentRequest request = requests.get(0);
            RequestTrigger trigger = mapStatusToTrigger(status);

            if (trigger != null && StateMachine.isValidTransition(request.getStatus(), trigger)) {
                request.applyTransition(trigger);
                requestRepository.save(request);
                log.info("Request {} updated to {} (from Kafka status {})",
                        request.getRequestId(), request.getStatus(), status);
            }

        } catch (Exception e) {
            log.error("Failed to process status update: {}", e.getMessage(), e);
        }
    }

    private RequestTrigger mapStatusToTrigger(String status) {
        return switch (status) {
            case "SETTLED" -> RequestTrigger.KAFKA_SETTLED;
            case "REJECTED" -> RequestTrigger.KAFKA_REJECTED;
            case "HELD" -> RequestTrigger.KAFKA_HELD;
            case "RETURN_INITIATED" -> RequestTrigger.KAFKA_RETURN_INITIATED;
            case "RETURNED" -> RequestTrigger.KAFKA_RETURNED;
            default -> {
                log.warn("Unknown payment status from Kafka: {}", status);
                yield null;
            }
        };
    }
}
