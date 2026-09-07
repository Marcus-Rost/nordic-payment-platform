package com.jrpay.fraud;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jrpay.domain.payment.VoPResult;
import com.jrpay.fraud.scoring.ScoringPipeline;
import com.jrpay.fraud.scoring.ScoringRequest;
import com.jrpay.fraud.scoring.ScoringResult;
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
 * Kafka consumer for fraud check requests from A2.
 * Runs the scoring pipeline and produces responses.
 */
@Component
public class FraudCheckKafkaConsumer {

    private static final Logger log = LoggerFactory.getLogger(FraudCheckKafkaConsumer.class);

    private final ScoringPipeline scoringPipeline;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public FraudCheckKafkaConsumer(ScoringPipeline scoringPipeline,
                                  KafkaTemplate<String, String> kafkaTemplate,
                                  ObjectMapper objectMapper) {
        this.scoringPipeline = scoringPipeline;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(
            topics = {"payment.fraud-check-request", "payment.instant-fraud-check-request"},
            groupId = "a5-fraud-scoring"
    )
    public void handleFraudCheckRequest(String message) {
        try {
            JsonNode json = objectMapper.readTree(message);

            ScoringRequest request = new ScoringRequest(
                    json.path("transactionId").asText(),
                    json.path("debtorAccountId").asText(),
                    json.path("creditorAccountId").asText(),
                    json.path("creditorName").asText(),
                    new BigDecimal(json.path("amount").asText("0")),
                    json.path("currency").asText("NOK"),
                    json.path("paymentMethod").asText("INSTANT"),
                    parseVoPResult(json.path("vopResult").asText(null)),
                    json.path("vopPayerOverride").asBoolean(false)
            );

            ScoringResult result = scoringPipeline.score(request);

            // Determine response topic based on input topic
            String responseTopic = "payment.instant-fraud-check-response";

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("transactionId", result.transactionId());
            response.put("sanctionsResult", result.sanctionsResult().name());
            response.put("sanctionsMatchedEntry", result.sanctionsMatchedEntry());
            response.put("sanctionsListVersion", result.sanctionsListVersion());
            response.put("decision", result.decision().name());
            response.put("fraudScore", result.fraudScore());
            response.put("velocityScore", result.velocityScore());
            response.put("velocityTriggeredRules", result.velocityTriggeredRules());
            response.put("scoringLatencyMs", result.scoringLatencyMs());
            response.put("timestamp", Instant.now().toString());

            String responseJson = objectMapper.writeValueAsString(response);
            kafkaTemplate.send(responseTopic, request.transactionId(), responseJson);

            log.info("Fraud check completed for {}: {} (score={})",
                    request.transactionId(), result.decision(), result.fraudScore());

        } catch (Exception e) {
            log.error("Failed to process fraud check request: {}", e.getMessage(), e);
        }
    }

    private VoPResult parseVoPResult(String value) {
        if (value == null || value.isBlank() || "null".equals(value)) {
            return null;
        }
        try {
            return VoPResult.valueOf(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
