package com.jrpay.notifications.preferences;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jrpay.notifications.NotificationsIntegrationTestBase;
import com.jrpay.notifications.domain.Audience;
import com.jrpay.notifications.domain.NotificationCategory;
import com.jrpay.notifications.domain.NotificationChannel;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;

@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class PreferencesServiceIntegrationTest extends NotificationsIntegrationTestBase {

    @Autowired PreferencesService service;

    @Test
    void firstAccessSeedsDefaults() {
        UUID userId = UUID.randomUUID();
        var entity = service.getOrCreate(userId, Audience.customer);
        assertThat(entity.getVersion()).isZero();
        assertThat(entity.getCategories()).containsKey(NotificationCategory.PAYMENT_STATUS.name());
        assertThat(service.isEnabled(userId, NotificationCategory.PAYMENT_STATUS, NotificationChannel.INBOX))
                .isTrue();
        // SECURITY_EVENTS is locked-on regardless of channel toggles.
        assertThat(service.isEnabled(userId, NotificationCategory.SECURITY_EVENTS, NotificationChannel.SMS))
                .isTrue();
    }

    @Test
    void replaceWithMatchingVersionBumpsVersionAndAppliesOverrides() {
        UUID userId = UUID.randomUUID();
        service.getOrCreate(userId, Audience.customer);

        Map<String, Object> categories = new LinkedHashMap<>();
        Map<String, Object> paymentCell = new LinkedHashMap<>();
        paymentCell.put("inbox", false);  // server should pin back to true
        paymentCell.put("email", false);
        paymentCell.put("sms", false);
        paymentCell.put("push", false);
        categories.put(NotificationCategory.PAYMENT_STATUS.name(), paymentCell);

        var updated = service.replace(userId, Audience.customer,
                new PreferencesService.Update(0, Map.of(), categories));
        assertThat(updated.getVersion()).isEqualTo(1);
        @SuppressWarnings("unchecked")
        Map<String, Object> normalised = (Map<String, Object>) updated.getCategories()
                .get(NotificationCategory.PAYMENT_STATUS.name());
        assertThat(normalised.get("inbox")).isEqualTo(true);
        assertThat(normalised.get("email")).isEqualTo(false);
    }

    @Test
    void replaceWithStaleVersionThrows() {
        UUID userId = UUID.randomUUID();
        service.getOrCreate(userId, Audience.customer);
        service.replace(userId, Audience.customer,
                new PreferencesService.Update(0, Map.of(), Map.of()));
        assertThatThrownBy(() -> service.replace(userId, Audience.customer,
                new PreferencesService.Update(0, Map.of(), Map.of())))
                .isInstanceOf(PreferencesService.ConcurrencyConflictException.class);
    }
}
