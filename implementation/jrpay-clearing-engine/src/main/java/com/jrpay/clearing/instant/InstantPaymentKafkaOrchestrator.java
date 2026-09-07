package com.jrpay.clearing.instant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jrpay.domain.payment.FraudDecision;
import com.jrpay.domain.payment.SanctionsResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Kafka orchestrator for the instant payment pipeline.
 * Consumes instructions, coordinates with A1 (reservations) and A5 (fraud),
 * and publishes settlement events and status updates.
 *
 * This is the integration layer that wires the InstantPaymentProcessor
 * (domain logic) to the Kafka message bus.
 */
@Component
public class InstantPaymentKafkaOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(InstantPaymentKafkaOrchestrator.class);

    private final InstantPaymentProcessor processor;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public InstantPaymentKafkaOrchestrator(InstantPaymentProcessor processor,
                                           KafkaTemplate<String, String> kafkaTemplate,
                                           ObjectMapper objectMapper) {
        this.processor = processor;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * Step 1: Consume payment instructions from A3/A4.
     */
    @KafkaListener(
            topics = "payment.instant-instructions",
            groupId = "a2-instant-clearing"
    )
    public void handleInstruction(String message) {
        try {
            JsonNode json = objectMapper.readTree(message);
            String transactionId = json.path("transactionId").asText();

            log.info("Received instant instruction: {}", transactionId);

            // Receive and validate
            ClearingRecord record = processor.receiveInstruction(
                    transactionId,
                    json.path("debtorIban").asText(),
                    json.path("creditorIban").asText(),
                    json.path("creditorName").asText(),
                    new BigDecimal(json.path("amount").asText("0")),
                    json.path("currency").asText("NOK"),
                    json.path("vopResult").asText(null),
                    json.path("vopPayerOverride").asBoolean(false)
            );

            if (record.getStatus() == PaymentState.REJECTED) {
                publishStatusUpdate(record);
                return;
            }

            // Request balance reservation from A1
            record = processor.requestReservation(transactionId);
            publishReservationRequest(record);

        } catch (Exception e) {
            log.error("Failed to process instruction: {}", e.getMessage(), e);
        }
    }

    /**
     * Step 3: Handle reservation response from A1.
     */
    @KafkaListener(
            topics = "payment.balance-reservation-response",
            groupId = "a2-instant-clearing"
    )
    public void handleReservationResponse(String message) {
        try {
            JsonNode json = objectMapper.readTree(message);
            String transactionId = json.path("transactionId").asText();
            String result = json.path("result").asText();
            String reason = json.path("reason").asText("");

            log.info("Reservation response for {}: {}", transactionId, result);

            boolean confirmed = "CONFIRMED".equals(result);
            ClearingRecord record = processor.handleReservationResponse(
                    transactionId, confirmed, reason);

            if (record.getStatus() == PaymentState.REJECTED) {
                publishStatusUpdate(record);
                return;
            }

            // Request fraud check from A5
            publishFraudCheckRequest(record);

        } catch (Exception e) {
            log.error("Failed to process reservation response: {}", e.getMessage(), e);
        }
    }

    /**
     * Step 4: Handle fraud/sanctions response from A5.
     */
    @KafkaListener(
            topics = "payment.instant-fraud-check-response",
            groupId = "a2-instant-clearing"
    )
    public void handleFraudResponse(String message) {
        try {
            JsonNode json = objectMapper.readTree(message);
            String transactionId = json.path("transactionId").asText();

            SanctionsResult sanctionsResult = SanctionsResult.valueOf(
                    json.path("sanctionsResult").asText("CLEAR"));
            FraudDecision fraudDecision = FraudDecision.valueOf(
                    json.path("decision").asText("PASS"));
            double fraudScore = json.path("fraudScore").asDouble(0.0);

            log.info("Fraud response for {}: sanctions={}, decision={}, score={}",
                    transactionId, sanctionsResult, fraudDecision, fraudScore);

            ClearingRecord record = processor.handleFraudResponse(
                    transactionId, sanctionsResult, fraudDecision, fraudScore);

            if (record.getStatus() == PaymentState.REJECTED) {
                publishReleaseReservation(record);
                publishStatusUpdate(record);
                return;
            }

            if (record.getStatus() == PaymentState.SETTLING) {
                // Settle: publish settlement event + confirm reservation + status update
                record = processor.settle(transactionId);
                publishSettlementEvent(record);
                publishConfirmReservation(record);
                publishStatusUpdate(record);
            }

        } catch (Exception e) {
            log.error("Failed to process fraud response: {}", e.getMessage(), e);
        }
    }

    // === Kafka Publishers ===

    private void publishReservationRequest(ClearingRecord record) {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("transactionId", record.getTransactionId());
        msg.put("accountId", record.getDebtorIban());
        msg.put("operation", "RESERVE");
        msg.put("amount", record.getAmount().toPlainString());
        msg.put("currency", record.getCurrency());
        msg.put("reservationId", record.getReservationId());
        msg.put("timestamp", Instant.now().toString());

        send("payment.balance-reservation", record.getDebtorIban(), msg);
    }

    private void publishFraudCheckRequest(ClearingRecord record) {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("transactionId", record.getTransactionId());
        msg.put("debtorAccountId", record.getDebtorIban());
        msg.put("creditorAccountId", record.getCreditorIban());
        msg.put("creditorName", record.getCreditorName());
        msg.put("amount", record.getAmount().toPlainString());
        msg.put("currency", record.getCurrency());
        msg.put("paymentMethod", "INSTANT");
        msg.put("vopResult", record.getVopResult());
        msg.put("vopPayerOverride", record.getVopPayerOverride());
        msg.put("timestamp", Instant.now().toString());

        send("payment.instant-fraud-check-request", record.getTransactionId(), msg);
    }

    private void publishSettlementEvent(ClearingRecord record) {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("transactionId", record.getTransactionId());
        msg.put("operation", "SETTLE");
        msg.put("debtorAccountId", record.getDebtorIban());
        msg.put("creditorAccountId", record.getCreditorIban());
        msg.put("amount", record.getAmount().toPlainString());
        msg.put("currency", record.getCurrency());
        msg.put("timestamp", Instant.now().toString());

        send("payment.instant-settlement-events", record.getTransactionId(), msg);
    }

    private void publishConfirmReservation(ClearingRecord record) {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("transactionId", record.getTransactionId());
        msg.put("accountId", record.getDebtorIban());
        msg.put("operation", "CONFIRM");
        msg.put("reservationId", record.getReservationId());
        msg.put("timestamp", Instant.now().toString());

        send("payment.balance-reservation", record.getDebtorIban(), msg);
    }

    private void publishReleaseReservation(ClearingRecord record) {
        if (record.getReservationId() == null) return;

        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("transactionId", record.getTransactionId());
        msg.put("accountId", record.getDebtorIban());
        msg.put("operation", "RELEASE");
        msg.put("reservationId", record.getReservationId());
        msg.put("timestamp", Instant.now().toString());

        send("payment.balance-reservation", record.getDebtorIban(), msg);
    }

    private void publishStatusUpdate(ClearingRecord record) {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("transactionId", record.getTransactionId());
        msg.put("status", record.getStatus() == PaymentState.SETTLED ? "SETTLED" : "REJECTED");
        msg.put("reason", record.getRejectionReason());
        msg.put("timestamp", Instant.now().toString());

        send("payment.status-updates", record.getTransactionId(), msg);
    }

    private void send(String topic, String key, Map<String, Object> message) {
        try {
            kafkaTemplate.send(topic, key, objectMapper.writeValueAsString(message));
        } catch (Exception e) {
            log.error("Failed to publish to {}: {}", topic, e.getMessage());
        }
    }
}
