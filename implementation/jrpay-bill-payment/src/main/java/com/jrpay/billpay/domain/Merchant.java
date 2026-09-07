package com.jrpay.billpay.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

/**
 * Cached AvtaleGiro merchant registry entry. Production refreshes nightly
 * from Bits; the demo seed in {@code V002} provides a small set.
 */
@Entity
@Table(name = "merchants")
public class Merchant {

    @Id
    @Column(name = "merchant_id", length = 64)
    private String merchantId;

    @Column(name = "avtale_id", nullable = false, length = 7, unique = true)
    private String avtaleId;

    @Column(name = "display_name", nullable = false, length = 140)
    private String displayName;

    @Column(name = "trading_name", length = 140)
    private String tradingName;

    @Column(name = "iban", nullable = false, length = 34)
    private String iban;

    @Column(name = "bic", length = 11)
    private String bic;

    @Column(name = "logo_url", length = 500)
    private String logoUrl;

    /** Comma-separated {@link MandateFrequency} values. */
    @Column(name = "accepted_frequencies", nullable = false, columnDefinition = "TEXT")
    private String acceptedFrequencies;

    @Column(name = "registry_updated_at", nullable = false)
    private Instant registryUpdatedAt;

    protected Merchant() {} // JPA

    public Merchant(String merchantId, String avtaleId, String displayName, String tradingName,
                    String iban, String bic, String logoUrl, String acceptedFrequencies) {
        this.merchantId = merchantId;
        this.avtaleId = avtaleId;
        this.displayName = displayName;
        this.tradingName = tradingName;
        this.iban = iban;
        this.bic = bic;
        this.logoUrl = logoUrl;
        this.acceptedFrequencies = acceptedFrequencies;
        this.registryUpdatedAt = Instant.now();
    }

    public String getMerchantId() { return merchantId; }
    public String getAvtaleId() { return avtaleId; }
    public String getDisplayName() { return displayName; }
    public String getTradingName() { return tradingName; }
    public String getIban() { return iban; }
    public String getBic() { return bic; }
    public String getLogoUrl() { return logoUrl; }
    public Instant getRegistryUpdatedAt() { return registryUpdatedAt; }

    public List<MandateFrequency> getAcceptedFrequencies() {
        if (acceptedFrequencies == null || acceptedFrequencies.isBlank()) {
            return List.of();
        }
        return Arrays.stream(acceptedFrequencies.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(MandateFrequency::valueOf)
                .toList();
    }

    public boolean accepts(MandateFrequency frequency) {
        return getAcceptedFrequencies().contains(frequency);
    }
}
