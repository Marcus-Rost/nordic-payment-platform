package com.jrpay.fraud.casework.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "fraud_cases")
public class FraudCaseEntity {

    @Id
    @Column(name = "case_id")
    private UUID caseId;

    @Column(name = "payment_id", nullable = false)
    private UUID paymentId;

    @Column(name = "customer_user_id")
    private UUID customerUserId;

    @Column(name = "debtor_account_id", nullable = false, length = 34)
    private String debtorAccountId;

    @Column(name = "holder_name", length = 140)
    private String holderName;

    @Column(name = "creditor_iban", nullable = false, length = 34)
    private String creditorIban;

    @Column(name = "creditor_name", length = 140)
    private String creditorName;

    @Column(name = "amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "reference", length = 140)
    private String reference;

    @Column(name = "execution_mode", length = 16)
    private String executionMode;

    @Column(name = "score", nullable = false, precision = 5, scale = 4)
    private BigDecimal score;

    @Enumerated(EnumType.STRING)
    @Column(name = "score_band", nullable = false, length = 8)
    private ScoreBand scoreBand;

    @Column(name = "threshold", nullable = false, precision = 5, scale = 4)
    private BigDecimal threshold;

    @Column(name = "triggered_rules", columnDefinition = "TEXT")
    private String triggeredRules;

    @Column(name = "score_breakdown", columnDefinition = "TEXT")
    private String scoreBreakdown;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private Status status;

    @Column(name = "assignee")
    private UUID assignee;

    @Column(name = "initiated_at", nullable = false)
    private Instant initiatedAt;

    @Column(name = "sla_deadline", nullable = false)
    private Instant slaDeadline;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "decided_by")
    private UUID decidedBy;

    @Column(name = "decision_notes_summary", length = 140)
    private String decisionNotesSummary;

    @Column(name = "decision_notes_hash", length = 64)
    private String decisionNotesHash;

    @Column(name = "sca_session_id")
    private UUID scaSessionId;

    protected FraudCaseEntity() {}

    public FraudCaseEntity(UUID caseId, UUID paymentId, UUID customerUserId,
                           String debtorAccountId, String holderName,
                           String creditorIban, String creditorName,
                           BigDecimal amount, String currency, String reference,
                           String executionMode, BigDecimal score, BigDecimal threshold,
                           String triggeredRules, String scoreBreakdown,
                           Instant initiatedAt, Instant slaDeadline) {
        this.caseId = caseId;
        this.paymentId = paymentId;
        this.customerUserId = customerUserId;
        this.debtorAccountId = debtorAccountId;
        this.holderName = holderName;
        this.creditorIban = creditorIban;
        this.creditorName = creditorName;
        this.amount = amount;
        this.currency = currency;
        this.reference = reference;
        this.executionMode = executionMode;
        this.score = score;
        this.threshold = threshold;
        this.triggeredRules = triggeredRules;
        this.scoreBreakdown = scoreBreakdown;
        this.scoreBand = bandFor(score);
        this.status = Status.OPEN;
        this.initiatedAt = initiatedAt;
        this.slaDeadline = slaDeadline;
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    public static ScoreBand bandFor(BigDecimal score) {
        if (score.compareTo(new BigDecimal("0.85")) >= 0) return ScoreBand.HIGH;
        if (score.compareTo(new BigDecimal("0.60")) >= 0) return ScoreBand.MEDIUM;
        return ScoreBand.LOW;
    }

    public boolean isDecided() {
        return status == Status.CONFIRMED_FRAUD || status == Status.DISMISSED;
    }

    public boolean isSlaBreached(Instant now) {
        return !isDecided() && now.isAfter(slaDeadline);
    }

    public UUID getCaseId() { return caseId; }
    public UUID getPaymentId() { return paymentId; }
    public UUID getCustomerUserId() { return customerUserId; }
    public String getDebtorAccountId() { return debtorAccountId; }
    public String getHolderName() { return holderName; }
    public String getCreditorIban() { return creditorIban; }
    public String getCreditorName() { return creditorName; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public String getReference() { return reference; }
    public String getExecutionMode() { return executionMode; }
    public BigDecimal getScore() { return score; }
    public ScoreBand getScoreBand() { return scoreBand; }
    public BigDecimal getThreshold() { return threshold; }
    public String getTriggeredRules() { return triggeredRules; }
    public String getScoreBreakdown() { return scoreBreakdown; }
    public Status getStatus() { return status; }
    public UUID getAssignee() { return assignee; }
    public Instant getInitiatedAt() { return initiatedAt; }
    public Instant getSlaDeadline() { return slaDeadline; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getDecidedAt() { return decidedAt; }
    public UUID getDecidedBy() { return decidedBy; }
    public String getDecisionNotesSummary() { return decisionNotesSummary; }
    public String getDecisionNotesHash() { return decisionNotesHash; }
    public UUID getScaSessionId() { return scaSessionId; }

    public void assignTo(UUID operator) {
        this.assignee = operator;
        this.status = operator == null ? Status.OPEN : Status.ASSIGNED;
        this.updatedAt = Instant.now();
    }

    public void decide(Status decision, UUID operator, String notesHash, String notesSummary) {
        if (decision != Status.CONFIRMED_FRAUD && decision != Status.DISMISSED) {
            throw new IllegalArgumentException("decision must be CONFIRMED_FRAUD or DISMISSED");
        }
        this.status = decision;
        this.decidedAt = Instant.now();
        this.decidedBy = operator;
        this.decisionNotesHash = notesHash;
        this.decisionNotesSummary = notesSummary;
        this.updatedAt = this.decidedAt;
    }

    public void touch() { this.updatedAt = Instant.now(); }

    public enum ScoreBand { LOW, MEDIUM, HIGH }
    public enum Status { OPEN, ASSIGNED, INVESTIGATING, CONFIRMED_FRAUD, DISMISSED }
}
