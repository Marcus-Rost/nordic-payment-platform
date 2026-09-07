package com.jrpay.notifications;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.kafka.annotation.EnableKafka;

/**
 * JRPay Notifications Service — backs {@code customer-notifications.yaml}.
 *
 * <p>Hosts:
 * <ul>
 *   <li>Customer inbox CRUD ({@code /api/v1/customer/notifications}).
 *   <li>SSE stream + stream-token mint for cross-device sync.
 *   <li>Per-customer preferences matrix (channel × category) with optimistic
 *       concurrency.
 *   <li>Push device registration ({@code /devices}).
 *   <li>Channel verification re-send for email + phone.
 *   <li>Kafka projection of {@code payment.status-updates} (and future
 *       {@code consent.*} / {@code mandate.pre-debit} / {@code fraud.*} /
 *       {@code sanctions.*}) into the inbox + dispatch via the
 *       {@link com.jrpay.notifications.adapter.NotificationChannelAdapter}
 *       SPI.
 * </ul>
 */
@SpringBootApplication
@EnableKafka
public class NotificationsServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(NotificationsServiceApplication.class, args);
    }
}
