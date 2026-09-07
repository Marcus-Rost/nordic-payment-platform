package com.jrpay.ledger.command;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jrpay.domain.money.Currency;
import com.jrpay.domain.money.MonetaryAmount;
import com.jrpay.ledger.projection.ReservationEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Kafka consumer for balance reservation requests from A2.
 * Consumes from payment.balance-reservation, produces responses
 * to payment.balance-reservation-response.
 */
@Component
public class ReservationKafkaConsumer {

    private static final Logger log = LoggerFactory.getLogger(ReservationKafkaConsumer.class);
    private static final String RESPONSE_TOPIC = "payment.balance-reservation-response";

    private final AccountCommandHandler commandHandler;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public ReservationKafkaConsumer(AccountCommandHandler commandHandler,
                                   KafkaTemplate<String, String> kafkaTemplate,
                                   ObjectMapper objectMapper) {
        this.commandHandler = commandHandler;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(
            topics = "payment.balance-reservation",
            groupId = "a1-reservation-handler"
    )
    public void handleReservationRequest(String message) {
        try {
            JsonNode json = objectMapper.readTree(message);
            String operation = json.path("operation").asText();
            String transactionId = json.path("transactionId").asText();
            String accountId = json.path("accountId").asText();
            String reservationId = json.path("reservationId").asText();

            if ("RESERVE".equals(operation)) {
                handleReserve(json, transactionId, accountId, reservationId);
            } else if ("RELEASE".equals(operation)) {
                commandHandler.release(reservationId, "A2_RELEASE_REQUEST");
                sendResponse(transactionId, accountId, "RELEASED", null, null);
            } else if ("CONFIRM".equals(operation)) {
                commandHandler.confirm(reservationId);
                sendResponse(transactionId, accountId, "CONFIRMED", null, null);
            } else {
                log.warn("Unknown reservation operation: {}", operation);
            }
        } catch (Exception e) {
            log.error("Failed to process reservation request: {}", e.getMessage(), e);
        }
    }

    private void handleReserve(JsonNode json, String transactionId,
                               String accountId, String reservationId) {
        try {
            String amountStr = json.path("amount").asText();
            String currencyStr = json.path("currency").asText();
            Currency currency = Currency.fromCode(currencyStr);
            MonetaryAmount amount = MonetaryAmount.of(amountStr, currency);

            ReservationEntity reservation = commandHandler.reserve(
                    accountId, reservationId, transactionId, amount);

            sendResponse(transactionId, accountId, "CONFIRMED", null, null);
            log.info("Reservation {} confirmed for account {}", reservationId, accountId);
        } catch (Exception e) {
            log.warn("Reservation rejected for {}: {}", transactionId, e.getMessage());
            sendResponse(transactionId, accountId, "REJECTED", e.getMessage(), null);
        }
    }

    private void sendResponse(String transactionId, String accountId,
                              String result, String reason, String reservationId) {
        try {
            String response = objectMapper.writeValueAsString(java.util.Map.of(
                    "transactionId", transactionId,
                    "accountId", accountId,
                    "result", result,
                    "reason", reason != null ? reason : "",
                    "timestamp", java.time.Instant.now().toString()
            ));
            // Key by transactionId for A2 co-partitioning
            kafkaTemplate.send(RESPONSE_TOPIC, transactionId, response);
        } catch (Exception e) {
            log.error("Failed to send reservation response: {}", e.getMessage());
        }
    }
}
