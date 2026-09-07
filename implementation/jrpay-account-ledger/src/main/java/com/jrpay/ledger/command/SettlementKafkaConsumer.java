package com.jrpay.ledger.command;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jrpay.domain.money.Currency;
import com.jrpay.domain.money.MonetaryAmount;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Kafka consumer for settlement events from A2.
 * Applies double-entry transactions to the ledger.
 */
@Component
public class SettlementKafkaConsumer {

    private static final Logger log = LoggerFactory.getLogger(SettlementKafkaConsumer.class);

    private final AccountCommandHandler commandHandler;
    private final ObjectMapper objectMapper;

    public SettlementKafkaConsumer(AccountCommandHandler commandHandler,
                                  ObjectMapper objectMapper) {
        this.commandHandler = commandHandler;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(
            topics = {"payment.settlement-events", "payment.instant-settlement-events"},
            groupId = "a1-settlement-handler"
    )
    public void handleSettlementEvent(String message) {
        try {
            JsonNode json = objectMapper.readTree(message);
            String transactionId = json.path("transactionId").asText();
            String debitAccountId = json.path("debtorAccountId").asText();
            String creditAccountId = json.path("creditorAccountId").asText();
            String amountStr = json.path("amount").asText();
            String currencyStr = json.path("currency").asText();
            String operation = json.path("operation").asText("SETTLE");

            Currency currency = Currency.fromCode(currencyStr);
            MonetaryAmount amount = MonetaryAmount.of(amountStr, currency);

            if ("RETURN".equals(operation)) {
                // Reversal: credit the original debtor, debit the original creditor
                commandHandler.applyDoubleEntry(transactionId + "-return",
                        creditAccountId, debitAccountId, amount, "Payment return");
                log.info("Settlement return applied for {}", transactionId);
            } else {
                commandHandler.applyDoubleEntry(transactionId,
                        debitAccountId, creditAccountId, amount, "Payment settlement");
                log.info("Settlement applied for {}", transactionId);
            }
        } catch (Exception e) {
            log.error("Failed to process settlement event: {}", e.getMessage(), e);
        }
    }
}
