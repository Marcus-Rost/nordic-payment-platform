package com.jrpay.fraud.casework.stream;

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
 * In-memory fanout for case + sanctions stream events. Subscribers are the
 * operators connected to {@code /stream}; events are broadcast to all
 * subscribers in the matching scope (FRAUD or SANCTIONS).
 */
@Component
public class CaseworkStreamHub {

    private static final Logger log = LoggerFactory.getLogger(CaseworkStreamHub.class);

    private final ConcurrentHashMap<Scope, Set<Subscription>> subs = new ConcurrentHashMap<>();

    public Subscription register(Scope scope, UUID operatorId, String resourceFilter,
                                 SseEmitter emitter) {
        Subscription sub = new Subscription(scope, operatorId, resourceFilter, emitter);
        subs.computeIfAbsent(scope, k -> ConcurrentHashMap.newKeySet()).add(sub);
        emitter.onCompletion(() -> remove(sub));
        emitter.onTimeout(() -> { emitter.complete(); remove(sub); });
        emitter.onError(t -> {
            log.debug("SSE error for {}: {}", operatorId, t.toString());
            remove(sub);
        });
        return sub;
    }

    public void publish(Scope scope, String resourceId, String eventName, Map<String, Object> payload) {
        Set<Subscription> set = subs.get(scope);
        if (set == null || set.isEmpty()) return;
        for (Subscription sub : new HashSet<>(set)) {
            if (sub.resourceFilter() != null && !sub.resourceFilter().equals(resourceId)) continue;
            try {
                sub.emitter().send(SseEmitter.event().name(eventName).data(payload));
            } catch (IOException e) {
                log.debug("SSE send failed for {}: {}", sub.operatorId(), e.toString());
                sub.emitter().completeWithError(e);
                remove(sub);
            }
        }
    }

    public void heartbeatAll() {
        for (Set<Subscription> set : subs.values()) {
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
        Set<Subscription> set = subs.get(sub.scope());
        if (set != null) set.remove(sub);
    }

    public int subscriberCount(Scope scope) {
        Set<Subscription> set = subs.get(scope);
        return set == null ? 0 : set.size();
    }

    public enum Scope { FRAUD, SANCTIONS }

    public record Subscription(Scope scope, UUID operatorId, String resourceFilter, SseEmitter emitter) {}
}
