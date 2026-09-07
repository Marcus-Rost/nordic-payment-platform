package com.jrpay.psd2.customer.projection;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jrpay.psd2.customer.domain.CustomerAccountEntity;
import com.jrpay.psd2.customer.domain.CustomerAccountRepository;
import com.jrpay.psd2.customer.domain.CustomerTransactionEntity;
import com.jrpay.psd2.customer.domain.CustomerTransactionRepository;
import com.jrpay.psd2.customer.domain.ProcessedKafkaEvent;
import com.jrpay.psd2.customer.domain.ProcessedKafkaEventRepository;
import java.math.BigDecimal;
import java.time.Instant;
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
 * Bridges {@code ledger.events} into the A4 read-projection. The customer
 * binding is supplied by the (external) onboarding flow — for now an
 * unbound account stays unbound until a {@code account.customer-registered}
 * event ties it to a customer (Phase 2L). For the dev / smoke flow the
 * tests seed {@link CustomerAccountEntity} rows directly.
 */
@Component
public class LedgerEventProjector {

    private static final Logger log = LoggerFactory.getLogger(LedgerEventProjector.class);

    private final CustomerAccountRepository accounts;
    private final CustomerTransactionRepository transactions;
    private final ProcessedKafkaEventRepository processed;
    private final ObjectMapper mapper;

    public LedgerEventProjector(CustomerAccountRepository accounts,
                                CustomerTransactionRepository transactions,
                                ProcessedKafkaEventRepository processed,
                                ObjectMapper mapper) {
        this.accounts = accounts;
        this.transactions = transactions;
        this.processed = processed;
        this.mapper = mapper;
    }

    @KafkaListener(
            topics = "${jrpay.psd2.kafka.ledger-events-topic:ledger.events}",
            groupId = "${jrpay.psd2.kafka.group-id:jrpay-psd2-api}",
            containerFactory = "kafkaListenerContainerFactory")
    @Transactional
    public void onLedgerEvent(@Payload String value,
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
            String type = event.path("eventType").asText("");
            switch (type) {
                case "ACCOUNT_CREATED" -> applyAccountCreated(event);
                case "ACCOUNT_FROZEN"  -> applyAccountStatus(event, CustomerAccountEntity.Status.FROZEN);
                case "ACCOUNT_UNFROZEN" -> applyAccountStatus(event, CustomerAccountEntity.Status.ACTIVE);
                case "ACCOUNT_CLOSED"  -> applyAccountClosed(event);
                case "BALANCE_RESERVED" -> applyReservedDelta(event, true);
                case "BALANCE_RELEASED" -> applyReservedDelta(event, false);
                case "BALANCE_CONFIRMED" -> applyBalanceConfirmed(event);
                case "DOUBLE_ENTRY" -> applyDoubleEntry(event);
                default -> log.debug("Ignoring unhandled ledger event type {}", type);
            }
            processed.save(new ProcessedKafkaEvent(topic, partition, offset));
            ack.acknowledge();
        } catch (Exception e) {
            // Don't ack on transient JSON parsing problems — Kafka redelivers.
            log.warn("Failed to project ledger event at {}/{} offset {}: {}",
                    topic, partition, offset, e.getMessage());
            throw new RuntimeException(e);
        }
    }

    private void applyAccountCreated(JsonNode event) {
        String accountId = event.path("accountId").asText();
        String currency = event.path("currency").asText("NOK");
        UUID customerUserId = parseUuid(event.path("customerUserId").asText(null));
        if (customerUserId == null) {
            // Phase 2L will resolve via account.customer-registered. Until then the
            // row is unbound; the customer-facing endpoints will not surface it.
            return;
        }
        CustomerAccountEntity entity = accounts.findById(accountId)
                .orElse(new CustomerAccountEntity(accountId, customerUserId, currency));
        BigDecimal initial = parseAmountOrZero(event.path("initialBalance").asText(null));
        entity.setBookedBalance(initial);
        accounts.save(entity);
    }

    private void applyAccountStatus(JsonNode event, CustomerAccountEntity.Status newStatus) {
        accounts.findById(event.path("accountId").asText()).ifPresent(a -> {
            a.setStatus(newStatus);
            accounts.save(a);
        });
    }

    private void applyAccountClosed(JsonNode event) {
        accounts.findById(event.path("accountId").asText()).ifPresent(a -> {
            a.setStatus(CustomerAccountEntity.Status.CLOSED);
            a.setClosedAt(Instant.now());
            accounts.save(a);
        });
    }

    private void applyReservedDelta(JsonNode event, boolean increase) {
        accounts.findById(event.path("accountId").asText()).ifPresent(a -> {
            BigDecimal amount = parseAmountOrZero(event.path("amount").asText(null));
            BigDecimal next = increase ? a.getReservedAmount().add(amount)
                    : a.getReservedAmount().subtract(amount).max(BigDecimal.ZERO);
            a.setReservedAmount(next);
            accounts.save(a);
        });
    }

    private void applyBalanceConfirmed(JsonNode event) {
        accounts.findById(event.path("accountId").asText()).ifPresent(a -> {
            BigDecimal amount = parseAmountOrZero(event.path("amount").asText(null));
            BigDecimal nextReserved = a.getReservedAmount().subtract(amount).max(BigDecimal.ZERO);
            a.setReservedAmount(nextReserved);
            accounts.save(a);
        });
    }

    private void applyDoubleEntry(JsonNode event) {
        String txId = event.path("transactionId").asText();
        BigDecimal amount = parseAmountOrZero(event.path("amount").asText());
        String currency = event.path("currency").asText("NOK");
        String description = event.path("description").asText(null);
        String debit = event.path("debitAccountId").asText();
        String credit = event.path("creditAccountId").asText();
        Instant parsed = parseInstant(event.path("timestamp").asText(null));
        final Instant postedAt = parsed == null ? Instant.now() : parsed;

        accounts.findById(debit).ifPresent(d -> {
            d.setBookedBalance(d.getBookedBalance().subtract(amount).max(BigDecimal.ZERO));
            accounts.save(d);
            persistTxLeg(txId, debit, d.getCustomerUserId(),
                    CustomerTransactionEntity.Direction.DEBIT, amount, currency,
                    "SETTLED", null, credit, null, description, postedAt);
        });
        accounts.findById(credit).ifPresent(c -> {
            c.setBookedBalance(c.getBookedBalance().add(amount));
            accounts.save(c);
            persistTxLeg(txId, credit, c.getCustomerUserId(),
                    CustomerTransactionEntity.Direction.CREDIT, amount, currency,
                    "SETTLED", null, debit, null, description, postedAt);
        });
    }

    private void persistTxLeg(String txId, String accountId, UUID customerUserId,
                              CustomerTransactionEntity.Direction direction,
                              BigDecimal amount, String currency, String status,
                              String counterpartyName, String counterpartyIban,
                              String reference, String description, Instant postedAt) {
        if (customerUserId == null) return;
        CustomerTransactionEntity.Pk pk = new CustomerTransactionEntity.Pk(txId, accountId);
        if (transactions.existsById(pk)) return;
        transactions.save(new CustomerTransactionEntity(
                txId, accountId, customerUserId, direction, amount, currency,
                status, counterpartyName, counterpartyIban, reference, description, postedAt));
    }

    private static BigDecimal parseAmountOrZero(String s) {
        if (s == null || s.isBlank()) return BigDecimal.ZERO;
        try { return new BigDecimal(s); } catch (Exception e) { return BigDecimal.ZERO; }
    }

    private static UUID parseUuid(String s) {
        if (s == null || s.isBlank()) return null;
        try { return UUID.fromString(s); } catch (Exception e) { return null; }
    }

    private static Instant parseInstant(String s) {
        if (s == null || s.isBlank()) return null;
        try { return Instant.parse(s); } catch (Exception e) { return null; }
    }
}
