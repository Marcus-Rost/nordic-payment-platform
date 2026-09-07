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
 * Persistent identity for both customers and operators.
 *
 * <p>The {@code audience} discriminator separates customer subjects (resolved by
 * {@code sub} in customer-scoped tokens) from operator subjects (admin-scoped).
 * Username is the local identifier per audience — typically email for both —
 * with uniqueness enforced per audience.
 */
@Entity
@Table(name = "identity_users")
public class IdentityUser {

    @Id
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "audience", nullable = false, length = 16)
    private Audience audience;

    @Column(name = "username", nullable = false)
    private String username;

    @Column(name = "email")
    private String email;

    @Column(name = "password_hash", nullable = false, length = 120)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private UserStatus status;

    @Column(name = "role", length = 32)
    private String role;

    @Column(name = "failed_attempts", nullable = false)
    private int failedAttempts;

    @Column(name = "locked_until")
    private OffsetDateTime lockedUntil;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "last_login_at")
    private OffsetDateTime lastLoginAt;

    protected IdentityUser() {
    }

    public IdentityUser(UUID userId, Audience audience, String username, String email,
                        String passwordHash, UserStatus status, String role) {
        this.userId = userId;
        this.audience = audience;
        this.username = username;
        this.email = email;
        this.passwordHash = passwordHash;
        this.status = status;
        this.role = role;
        OffsetDateTime now = OffsetDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    public UUID getUserId() {
        return userId;
    }

    public Audience getAudience() {
        return audience;
    }

    public String getUsername() {
        return username;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
        this.updatedAt = OffsetDateTime.now();
    }

    public UserStatus getStatus() {
        return status;
    }

    public void setStatus(UserStatus status) {
        this.status = status;
        this.updatedAt = OffsetDateTime.now();
    }

    public String getRole() {
        return role;
    }

    public int getFailedAttempts() {
        return failedAttempts;
    }

    public void incrementFailedAttempts() {
        this.failedAttempts += 1;
        this.updatedAt = OffsetDateTime.now();
    }

    public void resetFailedAttempts() {
        this.failedAttempts = 0;
        this.updatedAt = OffsetDateTime.now();
    }

    public OffsetDateTime getLockedUntil() {
        return lockedUntil;
    }

    public void setLockedUntil(OffsetDateTime lockedUntil) {
        this.lockedUntil = lockedUntil;
        this.updatedAt = OffsetDateTime.now();
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public OffsetDateTime getLastLoginAt() {
        return lastLoginAt;
    }

    public void recordLogin() {
        OffsetDateTime now = OffsetDateTime.now();
        this.lastLoginAt = now;
        this.updatedAt = now;
    }

    public boolean isLockedNow() {
        return lockedUntil != null && lockedUntil.isAfter(OffsetDateTime.now());
    }

    public enum Audience {
        customer, admin
    }

    public enum UserStatus {
        PENDING_FIRST_LOGIN, ACTIVE, SUSPENDED, LOCKED, DELETED
    }
}
