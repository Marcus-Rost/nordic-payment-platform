package com.jrpay.notifications.inbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jrpay.notifications.NotificationsIntegrationTestBase;
import com.jrpay.notifications.adapter.NotificationDispatcher;
import com.jrpay.notifications.domain.Audience;
import com.jrpay.notifications.domain.Notification;
import com.jrpay.notifications.domain.NotificationCategory;
import com.jrpay.notifications.repository.NotificationPreferencesRepository;
import com.jrpay.notifications.repository.NotificationRepository;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;

@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class InboxDispatcherIntegrationTest extends NotificationsIntegrationTestBase {

    @Autowired NotificationDispatcher dispatcher;
    @Autowired InboxService inbox;
    @Autowired NotificationRepository notifications;
    @Autowired NotificationPreferencesRepository preferences;

    @Test
    void dispatchPersistsRowAndBootstrapsPreferences() {
        UUID userId = UUID.randomUUID();
        Notification n = Notification.builder()
                .notificationId(Ulid.generate())
                .userId(userId)
                .audience(Audience.customer)
                .category(NotificationCategory.PAYMENT_STATUS)
                .titleKey("notification.payment.settled.title")
                .bodySummaryKey("notification.payment.settled.body")
                .params(Map.of("amount", "100.00", "currency", "NOK"))
                .build();
        var result = dispatcher.dispatch(n);

        assertThat(notifications.findById(result.notification().getNotificationId())).isPresent();
        // Inbox is mandatory; dispatcher always returns at least one
        // channel result row (skipped or otherwise) per side-channel.
        assertThat(result.channelResults()).hasSize(3);
        assertThat(preferences.findById(userId)).isPresent();
    }

    @Test
    void securityEventsCannotBeSoftDeleted() {
        UUID userId = UUID.randomUUID();
        Notification n = Notification.builder()
                .notificationId(Ulid.generate())
                .userId(userId)
                .audience(Audience.customer)
                .category(NotificationCategory.SECURITY_EVENTS)
                .titleKey("notification.security.title")
                .bodySummaryKey("notification.security.body")
                .params(Map.of())
                .build();
        Notification persisted = inbox.persistNew(n);
        assertThatThrownBy(() -> inbox.updateState(userId, persisted.getNotificationId(),
                InboxService.ReadStateChange.NONE,
                InboxService.DeleteStateChange.SOFT_DELETE))
                .isInstanceOf(Notification.LockedNotificationException.class);
    }

    @Test
    void inboxQueryFiltersByCategoryAndReadState() {
        UUID userId = UUID.randomUUID();
        // Pre-create preferences row so the dispatcher can read them.
        Notification a = Notification.builder()
                .notificationId(Ulid.generate())
                .userId(userId)
                .audience(Audience.customer)
                .category(NotificationCategory.PAYMENT_STATUS)
                .titleKey("a").bodySummaryKey("a").build();
        Notification b = Notification.builder()
                .notificationId(Ulid.generate())
                .userId(userId)
                .audience(Audience.customer)
                .category(NotificationCategory.R2P_INCOMING)
                .titleKey("b").bodySummaryKey("b").build();
        dispatcher.dispatch(a);
        dispatcher.dispatch(b);

        var unread = inbox.list(new InboxService.InboxQuery(
                userId, java.util.List.of(NotificationCategory.PAYMENT_STATUS),
                InboxService.ReadState.UNREAD,
                null, null, null, false, 0, 25));
        assertThat(unread.items()).hasSize(1);
        assertThat(unread.items().get(0).getCategory()).isEqualTo(NotificationCategory.PAYMENT_STATUS);
        assertThat(unread.unreadCount()).isEqualTo(2);

        // Mark the PAYMENT_STATUS one read.
        inbox.updateState(userId, unread.items().get(0).getNotificationId(),
                InboxService.ReadStateChange.MARK_READ,
                InboxService.DeleteStateChange.NONE);
        var unreadAfter = inbox.list(new InboxService.InboxQuery(
                userId, null, InboxService.ReadState.UNREAD,
                null, null, null, false, 0, 25));
        assertThat(unreadAfter.unreadCount()).isEqualTo(1);
    }
}
