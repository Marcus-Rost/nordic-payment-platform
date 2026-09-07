package com.jrpay.notifications.sse;

import com.jrpay.notifications.domain.Notification;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * In-process pub-sub bus for SSE listeners. Each subscriber registers an
 * {@link SseEmitter} for a {@code (userId)}; this bus broadcasts events
 * scoped to that user across every active emitter so cross-device sync
 * works (UC-G80 §6.5).
 *
 * <p>Production deploys with multiple notification-service replicas would
 * back this with a Redis pub-sub channel; the in-process variant is
 * sufficient for the portfolio default + every test in this module.
 */
@Component
public class NotificationEventBus {

    private static final Logger log = LoggerFactory.getLogger(NotificationEventBus.class);

    private final ConcurrentHashMap<UUID, CopyOnWriteArrayList<SseEmitter>> subscribers =
            new ConcurrentHashMap<>();

    public SseEmitter subscribe(UUID userId, long timeoutMs) {
        SseEmitter emitter = new SseEmitter(timeoutMs);
        var list = subscribers.computeIfAbsent(userId, k -> new CopyOnWriteArrayList<>());
        list.add(emitter);
        emitter.onCompletion(() -> list.remove(emitter));
        emitter.onTimeout(() -> {
            list.remove(emitter);
            emitter.complete();
        });
        emitter.onError(e -> list.remove(emitter));
        return emitter;
    }

    public void publish(Event event) {
        var list = subscribers.get(event.userId);
        if (list == null || list.isEmpty()) return;
        for (SseEmitter emitter : list) {
            try {
                emitter.send(SseEmitter.event()
                        .name(event.name)
                        .id(event.eventId)
                        .data(event.payload()));
            } catch (Exception e) {
                log.debug("SSE delivery failed for user={}: {}", event.userId, e.toString());
                list.remove(emitter);
            }
        }
    }

    public int subscriberCount(UUID userId) {
        var list = subscribers.get(userId);
        return list == null ? 0 : list.size();
    }

    public static final class Event {
        private final UUID userId;
        private final String name;
        private final String eventId;
        private final java.util.Map<String, Object> payload;

        private Event(UUID userId, String name, String eventId,
                      java.util.Map<String, Object> payload) {
            this.userId = userId;
            this.name = name;
            this.eventId = eventId;
            this.payload = payload;
        }

        public java.util.Map<String, Object> payload() { return payload; }

        public static Event created(Notification n) {
            return new Event(n.getUserId(), "notification.new", n.getNotificationId(),
                    java.util.Map.of(
                            "id", n.getNotificationId(),
                            "category", n.getCategory().name(),
                            "createdAt", n.getCreatedAt()));
        }

        public static Event stateChanged(Notification n) {
            String name = n.isDeleted() ? "notification.deleted" : "notification.read-state-changed";
            // Map.of() rejects nulls — readAt / deletedAt may legitimately
            // be null when transitioning UNREAD or RESTORE.
            java.util.Map<String, Object> payload = new java.util.LinkedHashMap<>();
            payload.put("id", n.getNotificationId());
            payload.put("readAt", n.getReadAt());
            payload.put("deletedAt", n.getDeletedAt());
            return new Event(n.getUserId(), name, n.getNotificationId(), payload);
        }

        public static Event bulkReadStateChanged(UUID userId) {
            java.util.Map<String, Object> payload = new java.util.LinkedHashMap<>();
            payload.put("bulk", true);
            payload.put("at", OffsetDateTime.now());
            return new Event(userId, "notification.read-state-changed",
                    "bulk-" + System.currentTimeMillis(), payload);
        }
    }
}
