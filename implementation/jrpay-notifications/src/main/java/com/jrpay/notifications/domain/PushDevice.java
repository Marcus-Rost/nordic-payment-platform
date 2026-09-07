package com.jrpay.notifications.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "push_devices")
public class PushDevice {

    @Id
    @Column(name = "device_id", nullable = false, updatable = false)
    private UUID deviceId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "platform", nullable = false, length = 8)
    private Platform platform;

    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    @Column(name = "name", nullable = false, length = 140)
    private String name;

    @Column(name = "model", length = 140)
    private String model;

    @Column(name = "os_version", length = 64)
    private String osVersion;

    @Column(name = "app_version", length = 64)
    private String appVersion;

    @Column(name = "bundle_id", length = 140)
    private String bundleId;

    @Column(name = "registered_at", nullable = false, updatable = false)
    private OffsetDateTime registeredAt;

    @Column(name = "last_seen_at", nullable = false)
    private OffsetDateTime lastSeenAt;

    @Column(name = "revoked_at")
    private OffsetDateTime revokedAt;

    protected PushDevice() {
    }

    public PushDevice(UUID deviceId, UUID userId, Platform platform, String tokenHash,
                      String name, String model, String osVersion, String appVersion,
                      String bundleId) {
        this.deviceId = deviceId;
        this.userId = userId;
        this.platform = platform;
        this.tokenHash = tokenHash;
        this.name = name;
        this.model = model;
        this.osVersion = osVersion;
        this.appVersion = appVersion;
        this.bundleId = bundleId;
        OffsetDateTime now = OffsetDateTime.now();
        this.registeredAt = now;
        this.lastSeenAt = now;
    }

    public UUID getDeviceId() { return deviceId; }
    public UUID getUserId() { return userId; }
    public Platform getPlatform() { return platform; }
    public String getTokenHash() { return tokenHash; }
    public String getName() { return name; }
    public String getModel() { return model; }
    public String getOsVersion() { return osVersion; }
    public String getAppVersion() { return appVersion; }
    public String getBundleId() { return bundleId; }
    public OffsetDateTime getRegisteredAt() { return registeredAt; }
    public OffsetDateTime getLastSeenAt() { return lastSeenAt; }
    public OffsetDateTime getRevokedAt() { return revokedAt; }

    public void touch() {
        this.lastSeenAt = OffsetDateTime.now();
    }

    public void revoke() {
        this.revokedAt = OffsetDateTime.now();
    }

    public boolean isActive() {
        return revokedAt == null;
    }

    public enum Platform { APNS, FCM }
}
