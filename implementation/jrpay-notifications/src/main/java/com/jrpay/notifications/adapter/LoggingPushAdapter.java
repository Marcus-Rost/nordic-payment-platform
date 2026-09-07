package com.jrpay.notifications.adapter;

import com.jrpay.notifications.domain.Notification;
import com.jrpay.notifications.domain.NotificationChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "jrpay.notifications.adapter.push",
        name = "kind", havingValue = "logging", matchIfMissing = true)
public class LoggingPushAdapter implements NotificationChannelAdapter {

    private static final Logger log = LoggerFactory.getLogger(LoggingPushAdapter.class);

    @Override
    public NotificationChannel channel() {
        return NotificationChannel.PUSH;
    }

    @Override
    public Result dispatch(Notification notification, Recipient recipient) {
        if (recipient.pushDeviceTokens() == null || recipient.pushDeviceTokens().isEmpty()) {
            return Result.skipped(channel(), "no-devices");
        }
        log.info("push: notification={} category={} priority={} devices={}",
                notification.getNotificationId(),
                notification.getCategory(),
                notification.getPriority(),
                recipient.pushDeviceTokens().size());
        return Result.ok(channel());
    }
}
