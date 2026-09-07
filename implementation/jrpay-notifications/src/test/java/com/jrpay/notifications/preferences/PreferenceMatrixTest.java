package com.jrpay.notifications.preferences;

import static org.assertj.core.api.Assertions.assertThat;

import com.jrpay.notifications.domain.NotificationCategory;
import com.jrpay.notifications.domain.NotificationChannel;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PreferenceMatrixTest {

    @Test
    void inboxIsAlwaysOnAndLockedAfterApplyOverrides() {
        Map<String, Object> categories = PreferenceMatrix.defaultCategories();
        // Customer attempts to disable inbox on PAYMENT_STATUS — server pins back.
        @SuppressWarnings("unchecked")
        Map<String, Object> cell = (Map<String, Object>) categories.get(NotificationCategory.PAYMENT_STATUS.name());
        cell.put("inbox", false);
        Map<String, Object> normalised = PreferenceMatrix.applyOverrides(categories);
        @SuppressWarnings("unchecked")
        Map<String, Object> normalisedCell = (Map<String, Object>) normalised.get(NotificationCategory.PAYMENT_STATUS.name());
        assertThat(normalisedCell.get("inbox")).isEqualTo(true);
    }

    @Test
    void securityEventsCannotBeDisabled() {
        Map<String, Object> categories = PreferenceMatrix.defaultCategories();
        @SuppressWarnings("unchecked")
        Map<String, Object> cell = (Map<String, Object>) categories.get(NotificationCategory.SECURITY_EVENTS.name());
        cell.put("email", false);
        cell.put("sms", false);
        cell.put("push", false);
        cell.put("inbox", false);
        Map<String, Object> normalised = PreferenceMatrix.applyOverrides(categories);
        @SuppressWarnings("unchecked")
        Map<String, Object> normalisedCell = (Map<String, Object>) normalised.get(NotificationCategory.SECURITY_EVENTS.name());
        assertThat(normalisedCell)
                .containsEntry("inbox", true)
                .containsEntry("email", true)
                .containsEntry("sms", true)
                .containsEntry("push", true)
                .containsEntry("locked", List.of("INBOX", "EMAIL", "SMS", "PUSH"));
    }

    @Test
    void isEnabledShortCircuitsOnInbox() {
        Map<String, Object> categories = PreferenceMatrix.defaultCategories();
        // Force PAYMENT_STATUS inbox = false in the persisted matrix.
        @SuppressWarnings("unchecked")
        Map<String, Object> cell = (Map<String, Object>) categories.get(NotificationCategory.PAYMENT_STATUS.name());
        cell.put("inbox", false);
        // PreferenceMatrix#isEnabled does NOT short-circuit — that's the
        // dispatcher's job. The matrix returns the literal cell value here.
        boolean inbox = PreferenceMatrix.isEnabled(categories,
                NotificationCategory.PAYMENT_STATUS, NotificationChannel.INBOX);
        assertThat(inbox).isFalse();
        // After applyOverrides the cell pins inbox-on.
        Map<String, Object> normalised = PreferenceMatrix.applyOverrides(categories);
        boolean inboxNormalised = PreferenceMatrix.isEnabled(normalised,
                NotificationCategory.PAYMENT_STATUS, NotificationChannel.INBOX);
        assertThat(inboxNormalised).isTrue();
    }

    @Test
    void unknownCategoryDefaultsToInboxOnly() {
        Map<String, Object> categories = new LinkedHashMap<>();
        boolean inbox = PreferenceMatrix.isEnabled(categories,
                NotificationCategory.MARKETING, NotificationChannel.INBOX);
        boolean email = PreferenceMatrix.isEnabled(categories,
                NotificationCategory.MARKETING, NotificationChannel.EMAIL);
        assertThat(inbox).isTrue();
        assertThat(email).isFalse();
    }
}
