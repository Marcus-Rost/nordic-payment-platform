package com.jrpay.psd2.customer.payments.stream;

import java.io.IOException;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * In-memory fanout for {@code payment.status-updates} → customer SSE
 * subscribers. Keyed by {@code customerUserId}. A single customer may have
 * multiple connected clients (web + mobile); each one is its own emitter.
 */
@Component
public class PaymentStreamHub {

    private static final Logger log = LoggerFactory.getLogger(PaymentStreamHub.class);

    private final ConcurrentHashMap<UUID, Set<Subscription>> subscribers = new ConcurrentHashMap<>();

    public Subscription register(UUID customerUserId, String paymentScope, SseEmitter emitter) {
        Subscription sub = new Subscription(customerUserId, paymentScope, emitter);
        subscribers.computeIfAbsent(customerUserId, k -> ConcurrentHashMap.newKeySet()).add(sub);
        emitter.onCompletion(() -> remove(sub));
        emitter.onTimeout(() -> { emitter.complete(); remove(sub); });
        emitter.onError(t -> {
            log.debug("SSE error for {}: {}", customerUserId, t.toString());
            remove(sub);
        });
        return sub;
    }

    public void publish(UUID customerUserId, String paymentId, String eventName,
                        Map<String, Object> payload) {
        Set<Subscription> set = subscribers.get(customerUserId);
        if (set == null || set.isEmpty()) return;
        for (Subscription sub : new HashSet<>(set)) {
            if (sub.paymentScope() != null && !sub.paymentScope().equals(paymentId)) continue;
            try {
                sub.emitter().send(SseEmitter.event().name(eventName).data(payload));
            } catch (IOException e) {
                log.debug("SSE send failed for {} ({}): {}", customerUserId, eventName, e.toString());
                sub.emitter().completeWithError(e);
                remove(sub);
            }
        }
    }

    public void heartbeatAll() {
        for (Set<Subscription> set : subscribers.values()) {
            for (Subscription sub : new HashSet<>(set)) {
                try {
                    sub.emitter().send(SseEmitter.event().comment("hb"));
                } catch (IOException e) {
                    sub.emitter().completeWithError(e);
                    remove(sub);
                }
            }
        }
    }

    private void remove(Subscription sub) {
        Set<Subscription> set = subscribers.get(sub.customerUserId());
        if (set != null) set.remove(sub);
    }

    public int subscriberCount(UUID customerUserId) {
        Set<Subscription> set = subscribers.get(customerUserId);
        return set == null ? 0 : set.size();
    }

    public record Subscription(UUID customerUserId, String paymentScope, SseEmitter emitter) {}
}
