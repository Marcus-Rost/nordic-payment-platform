package com.jrpay.psd2.customer.payments.stream;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Sends an SSE heartbeat (comment frame) every 15 s per
 * {@code SSE_CONVENTIONS.md}. Each heartbeat keeps proxies / load balancers
 * from idling out the connection.
 */
@Configuration
@EnableScheduling
public class StreamHeartbeatScheduler {

    private final PaymentStreamHub hub;

    public StreamHeartbeatScheduler(PaymentStreamHub hub) { this.hub = hub; }

    @Scheduled(fixedDelayString = "${jrpay.psd2.stream.heartbeat-ms:15000}")
    public void heartbeat() {
        hub.heartbeatAll();
    }
}
