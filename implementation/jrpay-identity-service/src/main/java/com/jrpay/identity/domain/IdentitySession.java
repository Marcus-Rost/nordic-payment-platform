package com.jrpay.identity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Active OAuth2 session per device — surface for UC-G53 (active sessions
 * listing + per-device revoke). One row per logged-in device.
 */
@Entity
@Table(name = "identity_sessions")
public class IdentitySession {

    @Id
    @Column(name = "session_id", nullable = false, updatable = false)
    private UUID sessionId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "client_id", nullable = false, length = 64)
    private String clientId;

    @Column(name = "device_name", nullable = false, length = 140)
    private String deviceName;

    @Column(name = "user_agent", length = 512)
    private String userAgent;

    @Column(name = "ip_country", length = 2)
    private String ipCountry;

    @Enumerated(EnumType.STRING)
    @Column(name = "audience", nullable = false, length = 16)
    private IdentityUser.Audience audience;

    @Column(name = "acr_level", length = 32)
    private String acrLevel;

    @Column(name = "last_seen_at", nullable = false)
    private OffsetDateTime lastSeenAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "revoked_at")
    private OffsetDateTime revokedAt;

    @Column(name = "revoked_reason", length = 64)
    private String revokedReason;

    protected IdentitySession() {
    }

    public IdentitySession(UUID sessionId, UUID userId, String clientId, String deviceName,
                           String userAgent, String ipCountry, IdentityUser.Audience audience,
                           String acrLevel) {
        this.sessionId = sessionId;
        this.userId = userId;
        this.clientId = clientId;
        this.deviceName = deviceName;
        this.userAgent = userAgent;
        this.ipCountry = ipCountry;
        this.audience = audience;
        this.acrLevel = acrLevel;
        OffsetDateTime now = OffsetDateTime.now();
        this.createdAt = now;
        this.lastSeenAt = now;
    }

    public UUID getSessionId() {
        return sessionId;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getClientId() {
        return clientId;
    }

    public String getDeviceName() {
        return deviceName;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public String getIpCountry() {
        return ipCountry;
    }

    public IdentityUser.Audience getAudience() {
        return audience;
    }

    public String getAcrLevel() {
        return acrLevel;
    }

    public OffsetDateTime getLastSeenAt() {
        return lastSeenAt;
    }

    public void touch() {
        this.lastSeenAt = OffsetDateTime.now();
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getRevokedAt() {
        return revokedAt;
    }

    public String getRevokedReason() {
        return revokedReason;
    }

    public void revoke(String reason) {
        this.revokedAt = OffsetDateTime.now();
        this.revokedReason = reason;
    }

    public boolean isActive() {
        return revokedAt == null;
    }
}
