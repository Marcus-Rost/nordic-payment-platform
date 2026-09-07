package com.jrpay.fraud.casework.stream;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration
@EnableScheduling
public class StreamHeartbeatScheduler {

    private final CaseworkStreamHub hub;

    public StreamHeartbeatScheduler(CaseworkStreamHub hub) { this.hub = hub; }

    @Scheduled(fixedDelayString = "${jrpay.fraud.stream.heartbeat-ms:15000}")
    public void heartbeat() {
        hub.heartbeatAll();
    }
}
