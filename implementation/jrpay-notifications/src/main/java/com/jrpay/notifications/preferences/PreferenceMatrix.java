package com.jrpay.notifications.preferences;

import com.jrpay.notifications.domain.NotificationCategory;
import com.jrpay.notifications.domain.NotificationChannel;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Helpers around the JSONB-backed preferences matrix in
 * {@link com.jrpay.notifications.domain.NotificationPreferencesEntity}.
 *
 * <p>Two structural rules:
 * <ul>
 *   <li>{@link NotificationChannel#INBOX} is always enabled and locked across
 *       every category. Tampered PUTs are silently corrected.
 *   <li>Categories in {@link NotificationCategory#LOCKED} are enabled on
 *       every channel and the {@code locked} array contains every channel.
 * </ul>
 */
public final class PreferenceMatrix {

    private PreferenceMatrix() {
    }

    /** Default matrix shipped to first-time users (UC-G21 §6 step 2). */
    public static Map<String, Object> defaultChannels(String emailMasked, String phoneMasked,
                                                       boolean emailVerified, boolean phoneVerified) {
        Map<String, Object> channels = new LinkedHashMap<>();
        channels.put("inbox", Map.of("enabled", true, "locked", true));
        Map<String, Object> email = new LinkedHashMap<>();
        email.put("enabled", emailVerified);
        if (emailMasked != null) email.put("address", emailMasked);
        email.put("verified", emailVerified);
        channels.put("email", email);
        Map<String, Object> sms = new LinkedHashMap<>();
        sms.put("enabled", false);
        if (phoneMasked != null) sms.put("phone", phoneMasked);
        sms.put("verified", phoneVerified);
        channels.put("sms", sms);
        Map<String, Object> push = new LinkedHashMap<>();
        push.put("enabled", false);
        push.put("devices", List.of());
        channels.put("push", push);
        return channels;
    }

    public static Map<String, Object> defaultCategories() {
        Map<String, Object> all = new LinkedHashMap<>();
        for (NotificationCategory cat : NotificationCategory.values()) {
            all.put(cat.name(), defaultCellForCategory(cat));
        }
        return all;
    }

    public static Map<String, Object> defaultCellForCategory(NotificationCategory cat) {
        Map<String, Object> cell = new LinkedHashMap<>();
        boolean inboxOn = true;
        boolean emailOn = switch (cat) {
            case PAYMENT_STATUS, FRAUD_HOLD, SANCTIONS_BLOCK,
                 MANDATE_PRE_DEBIT, SECURITY_EVENTS -> true;
            default -> false;
        };
        boolean smsOn = switch (cat) {
            case FRAUD_HOLD, SECURITY_EVENTS -> true;
            default -> false;
        };
        boolean pushOn = switch (cat) {
            case PAYMENT_STATUS, FRAUD_HOLD, R2P_INCOMING,
                 MANDATE_PRE_DEBIT, SECURITY_EVENTS -> true;
            default -> false;
        };
        if (cat.isLocked()) {
            inboxOn = emailOn = smsOn = pushOn = true;
        }
        cell.put("inbox", inboxOn);
        cell.put("email", emailOn);
        cell.put("sms", smsOn);
        cell.put("push", pushOn);
        if (cat.isLocked()) {
            cell.put("locked", List.of("INBOX", "EMAIL", "SMS", "PUSH"));
        }
        return cell;
    }

    /**
     * Apply server-side overrides to a client-supplied matrix. Inbox-on /
     * inbox-locked is always restored; locked categories have every channel
     * pinned to true and the {@code locked} list re-applied.
     */
    public static Map<String, Object> applyOverrides(Map<String, Object> categories) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (NotificationCategory cat : NotificationCategory.values()) {
            Object existing = categories.get(cat.name());
            Map<String, Object> cell;
            if (existing instanceof Map<?, ?> m) {
                cell = new LinkedHashMap<>();
                for (var e : m.entrySet()) {
                    cell.put(String.valueOf(e.getKey()), e.getValue());
                }
            } else {
                cell = defaultCellForCategory(cat);
            }
            // Inbox always on across every category.
            cell.put("inbox", true);
            if (cat.isLocked()) {
                cell.put("inbox", true);
                cell.put("email", true);
                cell.put("sms", true);
                cell.put("push", true);
                cell.put("locked", List.of("INBOX", "EMAIL", "SMS", "PUSH"));
            }
            out.put(cat.name(), cell);
        }
        return out;
    }

    /**
     * Returns true if the per-category cell is set to true for the given
     * channel. Defaults to inbox-only when the category is missing.
     */
    public static boolean isEnabled(Map<String, Object> categories,
                                    NotificationCategory category,
                                    NotificationChannel channel) {
        Object cell = categories.get(category.name());
        if (!(cell instanceof Map<?, ?> map)) {
            return channel == NotificationChannel.INBOX;
        }
        Object value = map.get(channel.name().toLowerCase());
        return Boolean.TRUE.equals(value);
    }
}
