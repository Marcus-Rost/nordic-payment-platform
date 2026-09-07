package com.jrpay.notifications.domain;

/**
 * Outbound delivery channels. Mirrors
 * {@code shared-schemas.yaml#/components/schemas/NotificationChannel}.
 *
 * <p>Inbox is mandatory and locked-on for every category; preferences may
 * disable any other channel per category (subject to {@link NotificationCategory#LOCKED}).
 */
public enum NotificationChannel {
    INBOX, EMAIL, SMS, PUSH;

    public boolean isMandatory() {
        return this == INBOX;
    }
}
