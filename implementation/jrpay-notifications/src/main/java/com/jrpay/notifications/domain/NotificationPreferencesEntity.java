package com.jrpay.notifications.domain;

import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.Type;

/**
 * Per-user preferences row (one per user). Channels + categories are stored
 * as JSONB so the matrix can grow without schema migrations.
 */
@Entity
@Table(name = "notification_preferences")
public class NotificationPreferencesEntity {

    @Id
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "audience", nullable = false, length = 16)
    private Audience audience;

    @Column(name = "version", nullable = false)
    private int version;

    @Type(JsonBinaryType.class)
    @Column(name = "channels_json", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> channels;

    @Type(JsonBinaryType.class)
    @Column(name = "categories_json", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> categories;

    @Column(name = "email_address", length = 254)
    private String emailAddress;

    @Column(name = "email_verified", nullable = false)
    private boolean emailVerified;

    @Column(name = "phone_number", length = 32)
    private String phoneNumber;

    @Column(name = "phone_verified", nullable = false)
    private boolean phoneVerified;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected NotificationPreferencesEntity() {
    }

    public NotificationPreferencesEntity(UUID userId, Audience audience,
                                         Map<String, Object> channels,
                                         Map<String, Object> categories) {
        this.userId = userId;
        this.audience = audience;
        this.version = 0;
        this.channels = new LinkedHashMap<>(channels);
        this.categories = new LinkedHashMap<>(categories);
        this.updatedAt = OffsetDateTime.now();
    }

    public UUID getUserId() { return userId; }
    public Audience getAudience() { return audience; }
    public int getVersion() { return version; }

    public Map<String, Object> getChannels() { return channels; }
    public Map<String, Object> getCategories() { return categories; }

    public String getEmailAddress() { return emailAddress; }
    public boolean isEmailVerified() { return emailVerified; }
    public String getPhoneNumber() { return phoneNumber; }
    public boolean isPhoneVerified() { return phoneVerified; }

    public OffsetDateTime getUpdatedAt() { return updatedAt; }

    public void replace(Map<String, Object> channels, Map<String, Object> categories) {
        this.channels = new LinkedHashMap<>(channels);
        this.categories = new LinkedHashMap<>(categories);
        this.version += 1;
        this.updatedAt = OffsetDateTime.now();
    }

    public void setEmail(String address, boolean verified) {
        this.emailAddress = address;
        this.emailVerified = verified;
        this.updatedAt = OffsetDateTime.now();
    }

    public void setPhone(String number, boolean verified) {
        this.phoneNumber = number;
        this.phoneVerified = verified;
        this.updatedAt = OffsetDateTime.now();
    }
}
