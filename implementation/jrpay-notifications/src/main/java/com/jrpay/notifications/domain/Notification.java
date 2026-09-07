package com.jrpay.notifications.domain;

import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.Type;

/**
 * Inbox row. The {@code notificationId} is a ULID (26-char Crockford-Base32)
 * to give the row a stable monotonic ordering across distributed inserts —
 * the SSE stream uses it as {@code Last-Event-ID} per
 * {@code SSE_CONVENTIONS.md}.
 */
@Entity
@Table(name = "notifications")
public class Notification {

    @Id
    @Column(name = "notification_id", nullable = false, updatable = false, length = 26)
    private String notificationId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "audience", nullable = false, length = 16)
    private Audience audience;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 40)
    private NotificationCategory category;

    @Enumerated(EnumType.STRING)
    @Column(name = "priority", nullable = false, length = 8)
    private Priority priority;

    @Column(name = "title_key", nullable = false, length = 120)
    private String titleKey;

    @Column(name = "body_summary_key", nullable = false, length = 120)
    private String bodySummaryKey;

    @Type(JsonBinaryType.class)
    @Column(name = "params_json", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> params;

    @Column(name = "deep_link", length = 512)
    private String deepLink;

    @Enumerated(EnumType.STRING)
    @Column(name = "action_hint", nullable = false, length = 16)
    private ActionHint actionHint;

    @Column(name = "locked", nullable = false)
    private boolean locked;

    @Column(name = "support_reference", length = 64)
    private String supportReference;

    @Column(name = "related_payment_id")
    private UUID relatedPaymentId;

    @Column(name = "related_r2p_request_id")
    private UUID relatedR2pRequestId;

    @Column(name = "related_consent_id")
    private UUID relatedConsentId;

    @Column(name = "related_mandate_id")
    private UUID relatedMandateId;

    @Column(name = "related_gdpr_request_id")
    private UUID relatedGdprRequestId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "read_at")
    private OffsetDateTime readAt;

    @Column(name = "deleted_at")
    private OffsetDateTime deletedAt;

    protected Notification() {
    }

    Notification(Builder b) {
        this.notificationId = b.notificationId;
        this.userId = b.userId;
        this.audience = b.audience;
        this.category = b.category;
        this.priority = b.priority;
        this.titleKey = b.titleKey;
        this.bodySummaryKey = b.bodySummaryKey;
        this.params = b.params;
        this.deepLink = b.deepLink;
        this.actionHint = b.actionHint;
        this.locked = b.category.isLocked();
        this.supportReference = b.supportReference;
        this.relatedPaymentId = b.relatedPaymentId;
        this.relatedR2pRequestId = b.relatedR2pRequestId;
        this.relatedConsentId = b.relatedConsentId;
        this.relatedMandateId = b.relatedMandateId;
        this.relatedGdprRequestId = b.relatedGdprRequestId;
        this.createdAt = b.createdAt == null ? OffsetDateTime.now() : b.createdAt;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getNotificationId() { return notificationId; }
    public UUID getUserId() { return userId; }
    public Audience getAudience() { return audience; }
    public NotificationCategory getCategory() { return category; }
    public Priority getPriority() { return priority; }
    public String getTitleKey() { return titleKey; }
    public String getBodySummaryKey() { return bodySummaryKey; }
    public Map<String, Object> getParams() { return params; }
    public String getDeepLink() { return deepLink; }
    public ActionHint getActionHint() { return actionHint; }
    public boolean isLocked() { return locked; }
    public String getSupportReference() { return supportReference; }
    public UUID getRelatedPaymentId() { return relatedPaymentId; }
    public UUID getRelatedR2pRequestId() { return relatedR2pRequestId; }
    public UUID getRelatedConsentId() { return relatedConsentId; }
    public UUID getRelatedMandateId() { return relatedMandateId; }
    public UUID getRelatedGdprRequestId() { return relatedGdprRequestId; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getReadAt() { return readAt; }
    public OffsetDateTime getDeletedAt() { return deletedAt; }

    public void markRead() {
        if (this.readAt == null) {
            this.readAt = OffsetDateTime.now();
        }
    }

    public void markUnread() {
        this.readAt = null;
    }

    public void markDeleted() {
        if (locked) {
            throw new LockedNotificationException(notificationId, category);
        }
        if (this.deletedAt == null) {
            this.deletedAt = OffsetDateTime.now();
        }
    }

    public void restore() {
        this.deletedAt = null;
    }

    public boolean isUnread() { return readAt == null; }
    public boolean isDeleted() { return deletedAt != null; }

    public enum Priority { info, warn, critical }

    /**
     * Stored as Java name in the DB; the spec wire form uses {@code act-now}
     * for {@link #ACT_NOW}. Controllers translate via {@link #wire} /
     * {@link #fromWire}.
     */
    public enum ActionHint {
        VIEW, ACT_NOW, NONE;

        public static ActionHint fromWire(String wire) {
            return switch (wire) {
                case "act-now" -> ACT_NOW;
                case "none" -> NONE;
                case "view" -> VIEW;
                default -> VIEW;
            };
        }

        public String wire() {
            return switch (this) {
                case VIEW -> "view";
                case ACT_NOW -> "act-now";
                case NONE -> "none";
            };
        }
    }

    public static class Builder {
        private String notificationId;
        private UUID userId;
        private Audience audience;
        private NotificationCategory category;
        private Priority priority = Priority.info;
        private String titleKey;
        private String bodySummaryKey;
        private Map<String, Object> params = Map.of();
        private String deepLink;
        private ActionHint actionHint = ActionHint.VIEW;
        private String supportReference;
        private UUID relatedPaymentId;
        private UUID relatedR2pRequestId;
        private UUID relatedConsentId;
        private UUID relatedMandateId;
        private UUID relatedGdprRequestId;
        private OffsetDateTime createdAt;

        public Builder notificationId(String v) { this.notificationId = v; return this; }
        public Builder userId(UUID v) { this.userId = v; return this; }
        public Builder audience(Audience v) { this.audience = v; return this; }
        public Builder category(NotificationCategory v) { this.category = v; return this; }
        public Builder priority(Priority v) { this.priority = v; return this; }
        public Builder titleKey(String v) { this.titleKey = v; return this; }
        public Builder bodySummaryKey(String v) { this.bodySummaryKey = v; return this; }
        public Builder params(Map<String, Object> v) { this.params = v; return this; }
        public Builder deepLink(String v) { this.deepLink = v; return this; }
        public Builder actionHint(ActionHint v) { this.actionHint = v; return this; }
        public Builder supportReference(String v) { this.supportReference = v; return this; }
        public Builder relatedPaymentId(UUID v) { this.relatedPaymentId = v; return this; }
        public Builder relatedR2pRequestId(UUID v) { this.relatedR2pRequestId = v; return this; }
        public Builder relatedConsentId(UUID v) { this.relatedConsentId = v; return this; }
        public Builder relatedMandateId(UUID v) { this.relatedMandateId = v; return this; }
        public Builder relatedGdprRequestId(UUID v) { this.relatedGdprRequestId = v; return this; }
        public Builder createdAt(OffsetDateTime v) { this.createdAt = v; return this; }
        public Notification build() { return new Notification(this); }
    }

    public static class LockedNotificationException extends RuntimeException {
        public LockedNotificationException(String notificationId, NotificationCategory category) {
            super("Notification " + notificationId + " is locked (" + category + ")");
        }
    }
}
