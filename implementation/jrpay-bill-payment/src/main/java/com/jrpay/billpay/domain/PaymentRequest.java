package com.jrpay.billpay.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * R2P payment request entity. Tracks the full lifecycle from creation to
 * settlement, plus the GUI-tier amendment / defer / replace metadata.
 */
@Entity
@Table(name = "payment_requests")
public class PaymentRequest {

    @Id
    @Column(name = "request_id", length = 36)
    private String requestId;

    @Column(name = "merchant_id", nullable = false, length = 50)
    private String merchantId;

    @Column(name = "merchant_name", nullable = false, length = 140)
    private String merchantName;

    @Column(name = "merchant_verified", nullable = false)
    private boolean merchantVerified;

    @Column(name = "merchant_logo_url", length = 500)
    private String merchantLogoUrl;

    @Column(name = "creditor_iban", nullable = false, length = 34)
    private String creditorIban;

    @Column(name = "creditor_name", nullable = false, length = 140)
    private String creditorName;

    @Column(name = "debtor_iban", length = 34)
    private String debtorIban;

    @Column(name = "customer_user_id")
    private UUID customerUserId;

    @Column(name = "amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "reference", length = 35)
    private String reference;

    @Column(name = "description", length = 140)
    private String description;

    @Column(name = "due_date")
    private LocalDate dueDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private RequestStatus status;

    @Column(name = "transaction_id", length = 36)
    private String transactionId;

    @Column(name = "payment_id", length = 36)
    private String paymentId;

    @Column(name = "scheduled_payment_id", length = 36)
    private String scheduledPaymentId;

    @Column(name = "mandate_id", length = 36)
    private String mandateId;

    @Column(name = "decline_reason", length = 50)
    private String declineReason;

    @Column(name = "decline_message", length = 255)
    private String declineMessage;

    @Column(name = "decline_note", length = 280)
    private String declineNote;

    @Column(name = "amendment_allowed", nullable = false)
    private boolean amendmentAllowed = true;

    @Column(name = "amendment_proposed_at")
    private Instant amendmentProposedAt;

    @Column(name = "amendment_proposed_amount", precision = 18, scale = 2)
    private BigDecimal amendmentProposedAmount;

    @Column(name = "amendment_proposed_due_date")
    private LocalDate amendmentProposedDueDate;

    @Column(name = "amendment_reason", length = 280)
    private String amendmentReason;

    @Column(name = "amendment_rejected", nullable = false)
    private boolean amendmentRejected;

    @Column(name = "replaced_by_request_id", length = 36)
    private String replacedByRequestId;

    @Column(name = "replaces_request_id", length = 36)
    private String replacesRequestId;

    @Column(name = "requested_execution_mode", length = 16)
    private String requestedExecutionMode;

    @Column(name = "invoice_pdf_url", length = 500)
    private String invoicePdfUrl;

    @Column(name = "invoice_pdf_expires_at")
    private Instant invoicePdfExpiresAt;

    @Column(name = "idempotency_key", length = 64, unique = true)
    private String idempotencyKey;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected PaymentRequest() {} // JPA

    public PaymentRequest(String requestId, String merchantId, String creditorIban,
                          String creditorName, BigDecimal amount, String currency,
                          String reference, String description, LocalDate dueDate,
                          String idempotencyKey) {
        this.requestId = requestId;
        this.merchantId = merchantId;
        this.merchantName = creditorName;
        this.creditorIban = creditorIban;
        this.creditorName = creditorName;
        this.amount = amount;
        this.currency = currency;
        this.reference = reference;
        this.description = description;
        this.dueDate = dueDate;
        this.idempotencyKey = idempotencyKey;
        this.status = RequestStatus.PENDING;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
        this.amendmentAllowed = true;
    }

    public void applyTransition(RequestTrigger trigger) {
        this.status = StateMachine.transition(this.status, trigger);
        this.updatedAt = Instant.now();
    }

    /** Force-set status without state-machine validation. */
    public void setStatus(RequestStatus status) {
        this.status = status;
        this.updatedAt = Instant.now();
    }

    public String getRequestId() { return requestId; }
    public String getMerchantId() { return merchantId; }
    public String getMerchantName() { return merchantName; }
    public boolean isMerchantVerified() { return merchantVerified; }
    public String getMerchantLogoUrl() { return merchantLogoUrl; }
    public String getCreditorIban() { return creditorIban; }
    public String getCreditorName() { return creditorName; }
    public String getDebtorIban() { return debtorIban; }
    public UUID getCustomerUserId() { return customerUserId; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public String getReference() { return reference; }
    public String getDescription() { return description; }
    public LocalDate getDueDate() { return dueDate; }
    public RequestStatus getStatus() { return status; }
    public String getTransactionId() { return transactionId; }
    public String getPaymentId() { return paymentId; }
    public String getScheduledPaymentId() { return scheduledPaymentId; }
    public String getMandateId() { return mandateId; }
    public String getDeclineReason() { return declineReason; }
    public String getDeclineMessage() { return declineMessage; }
    public String getDeclineNote() { return declineNote; }
    public boolean isAmendmentAllowed() { return amendmentAllowed; }
    public Instant getAmendmentProposedAt() { return amendmentProposedAt; }
    public BigDecimal getAmendmentProposedAmount() { return amendmentProposedAmount; }
    public LocalDate getAmendmentProposedDueDate() { return amendmentProposedDueDate; }
    public String getAmendmentReason() { return amendmentReason; }
    public boolean isAmendmentRejected() { return amendmentRejected; }
    public String getReplacedByRequestId() { return replacedByRequestId; }
    public String getReplacesRequestId() { return replacesRequestId; }
    public String getRequestedExecutionMode() { return requestedExecutionMode; }
    public String getInvoicePdfUrl() { return invoicePdfUrl; }
    public Instant getInvoicePdfExpiresAt() { return invoicePdfExpiresAt; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void setMerchantName(String merchantName) { this.merchantName = merchantName; }
    public void setMerchantVerified(boolean v) { this.merchantVerified = v; }
    public void setMerchantLogoUrl(String url) { this.merchantLogoUrl = url; }
    public void setDebtorIban(String debtorIban) { this.debtorIban = debtorIban; }
    public void setCustomerUserId(UUID v) { this.customerUserId = v; }
    public void setTransactionId(String transactionId) { this.transactionId = transactionId; }
    public void setPaymentId(String paymentId) { this.paymentId = paymentId; }
    public void setScheduledPaymentId(String v) { this.scheduledPaymentId = v; }
    public void setMandateId(String mandateId) { this.mandateId = mandateId; }
    public void setDeclineReason(String declineReason) { this.declineReason = declineReason; }
    public void setDeclineMessage(String declineMessage) { this.declineMessage = declineMessage; }
    public void setDeclineNote(String v) { this.declineNote = v; }
    public void setAmendmentAllowed(boolean v) { this.amendmentAllowed = v; }
    public void setAmendmentProposedAt(Instant v) { this.amendmentProposedAt = v; }
    public void setAmendmentProposedAmount(BigDecimal v) { this.amendmentProposedAmount = v; }
    public void setAmendmentProposedDueDate(LocalDate v) { this.amendmentProposedDueDate = v; }
    public void setAmendmentReason(String v) { this.amendmentReason = v; }
    public void setAmendmentRejected(boolean v) { this.amendmentRejected = v; }
    public void setReplacedByRequestId(String v) { this.replacedByRequestId = v; }
    public void setReplacesRequestId(String v) { this.replacesRequestId = v; }
    public void setRequestedExecutionMode(String v) { this.requestedExecutionMode = v; }
    public void setInvoicePdfUrl(String v) { this.invoicePdfUrl = v; }
    public void setInvoicePdfExpiresAt(Instant v) { this.invoicePdfExpiresAt = v; }
    public void setAmount(BigDecimal amount) { this.amount = amount; this.updatedAt = Instant.now(); }
    public void setUpdatedAt(Instant v) { this.updatedAt = v; }
}
