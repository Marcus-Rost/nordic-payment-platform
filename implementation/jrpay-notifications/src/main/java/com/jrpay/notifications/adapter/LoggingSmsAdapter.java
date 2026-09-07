package com.jrpay.notifications.adapter;

import com.jrpay.notifications.domain.Notification;
import com.jrpay.notifications.domain.NotificationChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "jrpay.notifications.adapter.sms",
        name = "kind", havingValue = "logging", matchIfMissing = true)
public class LoggingSmsAdapter implements NotificationChannelAdapter {

    private static final Logger log = LoggerFactory.getLogger(LoggingSmsAdapter.class);

    @Override
    public NotificationChannel channel() {
        return NotificationChannel.SMS;
    }

    @Override
    public Result dispatch(Notification notification, Recipient recipient) {
        if (recipient.phoneNumber() == null || recipient.phoneNumber().isBlank()) {
            return Result.skipped(channel(), "no-phone");
        }
        log.info("sms: notification={} category={} priority={} recipient_hash={}",
                notification.getNotificationId(),
                notification.getCategory(),
                notification.getPriority(),
                Integer.toHexString(recipient.phoneNumber().hashCode()));
        return Result.ok(channel());
    }
}
