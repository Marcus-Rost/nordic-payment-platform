package com.jrpay.notifications.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * Notification categories — mirrors
 * {@code shared-schemas.yaml#/components/schemas/NotificationCategory}.
 *
 * <p>{@code SECURITY_EVENTS} is the locked category — every customer always
 * receives security events on every channel; the UC-G21 preferences UI greys
 * out the cells per FS.10. Enforced server-side by
 * {@link com.jrpay.notifications.preferences.PreferenceMatrix#applyOverrides}.
 */
public enum NotificationCategory {
    PAYMENT_STATUS,
    FRAUD_HOLD,
    SANCTIONS_BLOCK,
    R2P_INCOMING,
    CONSENT_EVENTS,
    MANDATE_PRE_DEBIT,
    SECURITY_EVENTS,
    GDPR_STATUS,
    ACCOUNT_FREEZE,
    SYSTEM_MAINTENANCE,
    PROFILE_CHANGE,
    MARKETING;

    /** Categories that cannot be disabled on any channel — UC-G21 AF-04. */
    public static final Set<NotificationCategory> LOCKED = EnumSet.of(SECURITY_EVENTS);

    public boolean isLocked() {
        return LOCKED.contains(this);
    }
}
