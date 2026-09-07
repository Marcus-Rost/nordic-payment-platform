package com.jrpay.clearing.instant;

import com.jrpay.domain.payment.FraudDecision;
import com.jrpay.domain.payment.PaymentStatus;
import com.jrpay.domain.payment.SanctionsResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Instant payment orchestrator — the core of A2.
 * Manages the payment state machine: validate → reserve → screen → settle.
 *
 * In production, this integrates with Kafka for async A1/A5 communication.
 * For testing, dependencies (reservation, fraud scoring) are injected as interfaces.
 */
@Service
public class InstantPaymentProcessor {

    private static final Logger log = LoggerFactory.getLogger(InstantPaymentProcessor.class);

    private final ClearingRecordRepository repository;
    private final BigDecimal maxAmount;

    public InstantPaymentProcessor(
            ClearingRecordRepository repository,
            @Value("${jrpay.clearing.instant.max-amount:100000}") BigDecimal maxAmount) {
        this.repository = repository;
        this.maxAmount = maxAmount;
    }

    /**
     * Step 1: Receive and validate a payment instruction.
     * Creates the clearing record in RECEIVED state.
     */
    @Transactional
    public ClearingRecord receiveInstruction(String transactionId, String debtorIban,
                                             String creditorIban, String creditorName,
                                             BigDecimal amount, String currency,
                                             String vopResult, Boolean vopPayerOverride) {
        // Idempotency: check if already received
        return repository.findById(transactionId).orElseGet(() -> {
            // Validate amount limit (SCT Inst max: EUR 100,000)
            if (amount.compareTo(maxAmount) > 0) {
                ClearingRecord record = new ClearingRecord(transactionId, debtorIban, creditorIban,
                        creditorName, amount, currency, "INSTANT", vopResult, vopPayerOverride);
                record.reject("AMOUNT_EXCEEDED");
                return repository.save(record);
            }

            ClearingRecord record = new ClearingRecord(transactionId, debtorIban, creditorIban,
                    creditorName, amount, currency, "INSTANT", vopResult, vopPayerOverride);
            return repository.save(record);
        });
    }

    /**
     * Step 2: Request balance reservation from A1.
     * Transitions RECEIVED → AWAITING_RESERVATION.
     */
    @Transactional
    public ClearingRecord requestReservation(String transactionId) {
        ClearingRecord record = getRecord(transactionId);
        if (record.getStatus() != PaymentState.RECEIVED) {
            log.info("Payment {} not in RECEIVED state, current: {}", transactionId, record.getStatus());
            return record;
        }
        if (record.getStatus() == PaymentState.REJECTED) {
            return record; // already rejected (e.g., amount exceeded)
        }

        String reservationId = deterministicId(transactionId, "balance-reservation");
        record.setReservationId(reservationId);
        record.transitionTo(PaymentState.AWAITING_RESERVATION);
        return repository.save(record);
    }

    /**
     * Step 3: Process reservation response from A1.
     * CONFIRMED → transition to AWAITING_FRAUD.
     * REJECTED → reject payment (insufficient balance or account frozen).
     */
    @Transactional
    public ClearingRecord handleReservationResponse(String transactionId, boolean confirmed,
                                                    String rejectionReason) {
        ClearingRecord record = getRecord(transactionId);
        if (record.getStatus() != PaymentState.AWAITING_RESERVATION) {
            log.info("Payment {} not AWAITING_RESERVATION, current: {}", transactionId, record.getStatus());
            return record;
        }

        if (confirmed) {
            record.transitionTo(PaymentState.AWAITING_FRAUD);
        } else {
            record.reject(rejectionReason != null ? rejectionReason : "RESERVATION_REJECTED");
        }
        return repository.save(record);
    }

    /**
     * Step 4: Process fraud/sanctions response from A5.
     * CLEAR + PASS → transition to SETTLING.
     * HIT or UNAVAILABLE → reject (sanctions cannot be skipped).
     * CLEAR + BLOCK → reject.
     * CLEAR + TIMEOUT → auto-pass fraud, proceed (sanctions confirmed clear).
     * TIMEOUT with sanctions != CLEAR → reject.
     */
    @Transactional
    public ClearingRecord handleFraudResponse(String transactionId,
                                              SanctionsResult sanctionsResult,
                                              FraudDecision fraudDecision,
                                              double fraudScore) {
        ClearingRecord record = getRecord(transactionId);
        if (record.getStatus() != PaymentState.AWAITING_FRAUD) {
            log.info("Payment {} not AWAITING_FRAUD, current: {}", transactionId, record.getStatus());
            return record;
        }

        record.setSanctionsResult(sanctionsResult.name());
        record.setFraudScore(fraudScore);

        // Sanctions HIT or UNAVAILABLE → REJECT (sanctions cannot be skipped)
        if (sanctionsResult == SanctionsResult.HIT) {
            record.reject("SANCTIONS_HIT");
            return repository.save(record);
        }
        if (sanctionsResult == SanctionsResult.UNAVAILABLE) {
            record.reject("SANCTIONS_UNAVAILABLE");
            return repository.save(record);
        }

        // Sanctions CLEAR — evaluate fraud decision
        switch (fraudDecision) {
            case BLOCK -> {
                record.reject("FRAUD_BLOCKED");
            }
            case PASS, REVIEW -> {
                record.transitionTo(PaymentState.SETTLING);
            }
            case TIMEOUT -> {
                // Sanctions CLEAR + fraud TIMEOUT → auto-pass with review flag
                record.transitionTo(PaymentState.SETTLING);
                log.warn("Payment {} auto-passed fraud (TIMEOUT) — flagged for post-settlement review",
                        transactionId);
            }
        }

        return repository.save(record);
    }

    /**
     * Step 5: Execute settlement — publish settlement event and confirm reservation.
     * SETTLING → SETTLED.
     */
    @Transactional
    public ClearingRecord settle(String transactionId) {
        ClearingRecord record = getRecord(transactionId);
        if (record.getStatus() != PaymentState.SETTLING) {
            log.info("Payment {} not SETTLING, current: {}", transactionId, record.getStatus());
            return record;
        }

        // In production: publish settlement event to Kafka, confirm reservation in A1
        record.transitionTo(PaymentState.SETTLED);
        return repository.save(record);
    }

    /**
     * Handle timeout — called by the punctuator or scheduled scanner.
     * If payment is in a non-terminal state past its deadline, reject it.
     */
    @Transactional
    public ClearingRecord handleTimeout(String transactionId, String reason) {
        ClearingRecord record = getRecord(transactionId);
        if (record.getStatus().isTerminal()) {
            return record;
        }
        record.reject(reason);
        return repository.save(record);
    }

    /**
     * Get the current status of a payment.
     */
    public PaymentStatus toPaymentStatus(ClearingRecord record) {
        return switch (record.getStatus()) {
            case SETTLED -> PaymentStatus.SETTLED;
            case REJECTED -> PaymentStatus.REJECTED;
            default -> null; // intermediate states not published
        };
    }

    /**
     * Deterministic ID derivation per INTERFACE_CONTRACTS B.3a.
     */
    static String deterministicId(String transactionId, String purpose) {
        return com.jrpay.clearing.util.HashUtil.sha256Hex(transactionId + purpose);
    }

    private ClearingRecord getRecord(String transactionId) {
        return repository.findById(transactionId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Clearing record not found: " + transactionId));
    }
}
