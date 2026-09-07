package com.jrpay.billpay.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Trusted beneficiary in a customer's address book (UC-G16). A beneficiary
 * begins as {@code PENDING_SCA} and becomes {@code ACTIVE} only after the
 * customer completes a UC-G51 SCA challenge bound to
 * {@code {beneficiaryId, name, iban}}.
 */
@Entity
@Table(name = "beneficiaries")
public class Beneficiary {

    @Id
    @Column(name = "beneficiary_id", length = 64)
    private String beneficiaryId;

    @Column(name = "customer_user_id", nullable = false)
    private UUID customerUserId;

    @Column(name = "name", nullable = false, length = 140)
    private String name;

    @Column(name = "iban", nullable = false, length = 34)
    private String iban;

    @Column(name = "bic", length = 11)
    private String bic;

    @Column(name = "bank_name", length = 140)
    private String bankName;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "notes", length = 140)
    private String notes;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private Status status;

    @Column(name = "vop_result_at_creation", length = 16)
    private String vopResultAtCreation;

    @Column(name = "vop_payer_override_at_creation")
    private Boolean vopPayerOverrideAtCreation;

    @Column(name = "sca_session_id")
    private UUID scaSessionId;

    @Column(name = "dl_hash", length = 64)
    private String dlHash;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "activated_at")
    private Instant activatedAt;

    @Column(name = "last_paid_at")
    private Instant lastPaidAt;

    @Column(name = "total_ytd", nullable = false, precision = 18, scale = 2)
    private BigDecimal totalYtd = BigDecimal.ZERO;

    @Column(name = "payments_count_ytd", nullable = false)
    private int paymentsCountYtd;

    protected Beneficiary() {} // JPA

    public Beneficiary(String beneficiaryId, UUID customerUserId, String name, String iban,
                       String bic, String bankName, String currency, String notes,
                       String vopResultAtCreation, Boolean vopPayerOverride,
                       UUID scaSessionId, String dlHash) {
        this.beneficiaryId = beneficiaryId;
        this.customerUserId = customerUserId;
        this.name = name;
        this.iban = iban;
        this.bic = bic;
        this.bankName = bankName;
        this.currency = currency;
        this.notes = notes;
        this.status = Status.PENDING_SCA;
        this.vopResultAtCreation = vopResultAtCreation;
        this.vopPayerOverrideAtCreation = vopPayerOverride;
        this.scaSessionId = scaSessionId;
        this.dlHash = dlHash;
        this.createdAt = Instant.now();
        this.totalYtd = BigDecimal.ZERO;
        this.paymentsCountYtd = 0;
    }

    public void activate() {
        this.status = Status.ACTIVE;
        this.activatedAt = Instant.now();
    }

    public String getBeneficiaryId() { return beneficiaryId; }
    public UUID getCustomerUserId() { return customerUserId; }
    public String getName() { return name; }
    public String getIban() { return iban; }
    public String getBic() { return bic; }
    public String getBankName() { return bankName; }
    public String getCurrency() { return currency; }
    public String getNotes() { return notes; }
    public Status getStatus() { return status; }
    public String getVopResultAtCreation() { return vopResultAtCreation; }
    public Boolean getVopPayerOverrideAtCreation() { return vopPayerOverrideAtCreation; }
    public UUID getScaSessionId() { return scaSessionId; }
    public String getDlHash() { return dlHash; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getActivatedAt() { return activatedAt; }
    public Instant getLastPaidAt() { return lastPaidAt; }
    public BigDecimal getTotalYtd() { return totalYtd; }
    public int getPaymentsCountYtd() { return paymentsCountYtd; }

    public void setNotes(String notes) { this.notes = notes; }

    public enum Status { PENDING_SCA, ACTIVE }
}
