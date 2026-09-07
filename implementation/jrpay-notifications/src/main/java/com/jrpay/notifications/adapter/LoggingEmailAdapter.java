package com.jrpay.notifications.adapter;

import com.jrpay.notifications.domain.Notification;
import com.jrpay.notifications.domain.NotificationChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Default email adapter — logs the dispatch with hashed recipient + the
 * notification's category/priority. Production deployments swap this with an
 * SMTP / Postmark / SES adapter via the {@code jrpay.notifications.adapter.email.kind}
 * property.
 */
@Component
@ConditionalOnProperty(prefix = "jrpay.notifications.adapter.email",
        name = "kind", havingValue = "logging", matchIfMissing = true)
public class LoggingEmailAdapter implements NotificationChannelAdapter {

    private static final Logger log = LoggerFactory.getLogger(LoggingEmailAdapter.class);

    @Override
    public NotificationChannel channel() {
        return NotificationChannel.EMAIL;
    }

    @Override
    public Result dispatch(Notification notification, Recipient recipient) {
        if (recipient.emailAddress() == null || recipient.emailAddress().isBlank()) {
            return Result.skipped(channel(), "no-address");
        }
        log.info("email: notification={} category={} priority={} recipient_hash={}",
                notification.getNotificationId(),
                notification.getCategory(),
                notification.getPriority(),
                shortHash(recipient.emailAddress()));
        return Result.ok(channel());
    }

    private static String shortHash(String value) {
        return Integer.toHexString(value.hashCode());
    }
}
