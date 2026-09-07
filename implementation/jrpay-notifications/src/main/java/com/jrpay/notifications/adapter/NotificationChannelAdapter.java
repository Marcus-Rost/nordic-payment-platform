package com.jrpay.notifications.adapter;

import com.jrpay.notifications.domain.Notification;
import com.jrpay.notifications.domain.NotificationChannel;

/**
 * SPI for outbound delivery channels (UC-G19 / UC-G20 / UC-G21 / UC-G29).
 * One implementation per channel; the dispatcher picks them up at startup
 * and routes per the customer's preference matrix.
 *
 * <p>Inbox is not exposed via this SPI — every notification persists to the
 * inbox unconditionally; this SPI is for the side-channels (email / SMS /
 * push). Swap the default {@code Logging*} implementations for production
 * adapters via {@code @ConditionalOnProperty}.
 */
public interface NotificationChannelAdapter {

    NotificationChannel channel();

    /**
     * Best-effort dispatch. Implementations should not throw — log and
     * return a {@link Result} with {@code success=false} instead, so a
     * single failing channel doesn't block the others.
     */
    Result dispatch(Notification notification, Recipient recipient);

    record Recipient(
            java.util.UUID userId,
            String emailAddress,
            String phoneNumber,
            java.util.List<String> pushDeviceTokens
    ) { }

    record Result(NotificationChannel channel, boolean success, String reason) {
        public static Result ok(NotificationChannel channel) {
            return new Result(channel, true, null);
        }
        public static Result skipped(NotificationChannel channel, String reason) {
            return new Result(channel, false, "skipped:" + reason);
        }
        public static Result failed(NotificationChannel channel, String reason) {
            return new Result(channel, false, reason);
        }
    }
}
