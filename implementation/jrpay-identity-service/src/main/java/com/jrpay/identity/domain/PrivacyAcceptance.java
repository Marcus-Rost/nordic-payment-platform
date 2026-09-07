package com.jrpay.identity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * Per-user acknowledgement of a privacy-notice version (UC-G45).
 */
@Entity
@Table(name = "identity_privacy_acceptance")
public class PrivacyAcceptance {

    @EmbeddedId
    private Pk pk;

    @Column(name = "accepted_at", nullable = false, updatable = false)
    private OffsetDateTime acceptedAt;

    protected PrivacyAcceptance() {
    }

    public PrivacyAcceptance(UUID userId, String noticeVersion) {
        this.pk = new Pk(userId, noticeVersion);
        this.acceptedAt = OffsetDateTime.now();
    }

    public UUID getUserId() {
        return pk.userId;
    }

    public String getNoticeVersion() {
        return pk.noticeVersion;
    }

    public OffsetDateTime getAcceptedAt() {
        return acceptedAt;
    }

    @Embeddable
    public static class Pk implements Serializable {
        @Column(name = "user_id", nullable = false)
        private UUID userId;

        @Column(name = "notice_version", nullable = false, length = 32)
        private String noticeVersion;

        public Pk() {
        }

        public Pk(UUID userId, String noticeVersion) {
            this.userId = userId;
            this.noticeVersion = noticeVersion;
        }

        public UUID getUserId() {
            return userId;
        }

        public String getNoticeVersion() {
            return noticeVersion;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Pk other)) return false;
            return Objects.equals(userId, other.userId)
                    && Objects.equals(noticeVersion, other.noticeVersion);
        }

        @Override
        public int hashCode() {
            return Objects.hash(userId, noticeVersion);
        }
    }
}
