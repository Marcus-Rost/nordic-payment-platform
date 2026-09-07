package com.jrpay.fraud.casework.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "sanctions_hits")
public class SanctionsHitEntity {

    @Id
    @Column(name = "hit_id")
    private UUID hitId;

    @Column(name = "payment_id", nullable = false)
    private UUID paymentId;

    @Column(name = "customer_user_id")
    private UUID customerUserId;

    @Column(name = "debtor_account_id", nullable = false, length = 34)
    private String debtorAccountId;

    @Column(name = "matched_entity_name", nullable = false, length = 280)
    private String matchedEntityName;

    @Column(name = "matched_list_id", nullable = false, length = 64)
    private String matchedListId;

    @Column(name = "match_score", nullable = false, precision = 5, scale = 4)
    private BigDecimal matchScore;

    @Column(name = "creditor_iban", nullable = false, length = 34)
    private String creditorIban;

    @Column(name = "creditor_name", nullable = false, length = 140)
    private String creditorName;

    @Column(name = "amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "reference", length = 140)
    private String reference;

    @Column(name = "matched_entity_payload", columnDefinition = "TEXT")
    private String matchedEntityPayload;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 28)
    private Status status;

    @Column(name = "assignee")
    private UUID assignee;

    @Column(name = "sla_deadline", nullable = false)
    private Instant slaDeadline;

    @Column(name = "initiated_at", nullable = false)
    private Instant initiatedAt;

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

    protected SanctionsHitEntity() {}

    public SanctionsHitEntity(UUID hitId, UUID paymentId, UUID customerUserId,
                              String debtorAccountId, String matchedEntityName,
                              String matchedListId, BigDecimal matchScore,
                              String creditorIban, String creditorName,
                              BigDecimal amount, String currency, String reference,
                              String matchedEntityPayload, Instant initiatedAt,
                              Instant slaDeadline) {
        this.hitId = hitId;
        this.paymentId = paymentId;
        this.customerUserId = customerUserId;
        this.debtorAccountId = debtorAccountId;
        this.matchedEntityName = matchedEntityName;
        this.matchedListId = matchedListId;
        this.matchScore = matchScore;
        this.creditorIban = creditorIban;
        this.creditorName = creditorName;
        this.amount = amount;
        this.currency = currency;
        this.reference = reference;
        this.matchedEntityPayload = matchedEntityPayload;
        this.status = Status.OPEN;
        this.initiatedAt = initiatedAt;
        this.slaDeadline = slaDeadline;
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    public boolean isDecided() {
        return status != Status.OPEN;
    }

    public UUID getHitId() { return hitId; }
    public UUID getPaymentId() { return paymentId; }
    public UUID getCustomerUserId() { return customerUserId; }
    public String getDebtorAccountId() { return debtorAccountId; }
    public String getMatchedEntityName() { return matchedEntityName; }
    public String getMatchedListId() { return matchedListId; }
    public BigDecimal getMatchScore() { return matchScore; }
    public String getCreditorIban() { return creditorIban; }
    public String getCreditorName() { return creditorName; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public String getReference() { return reference; }
    public String getMatchedEntityPayload() { return matchedEntityPayload; }
    public Status getStatus() { return status; }
    public UUID getAssignee() { return assignee; }
    public Instant getSlaDeadline() { return slaDeadline; }
    public Instant getInitiatedAt() { return initiatedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getDecidedAt() { return decidedAt; }
    public UUID getDecidedBy() { return decidedBy; }
    public String getDecisionNotesSummary() { return decisionNotesSummary; }
    public String getDecisionNotesHash() { return decisionNotesHash; }
    public UUID getScaSessionId() { return scaSessionId; }

    public void decide(Status decision, UUID operator, String notesHash, String notesSummary) {
        if (decision == Status.OPEN) throw new IllegalArgumentException("decision required");
        this.status = decision;
        this.decidedAt = Instant.now();
        this.decidedBy = operator;
        this.decisionNotesHash = notesHash;
        this.decisionNotesSummary = notesSummary;
        this.updatedAt = this.decidedAt;
    }

    public enum Status {
        OPEN, CONFIRMED_SANCTION, FALSE_POSITIVE, RELEASE_FOR_FRAUD_REVIEW
    }
}
