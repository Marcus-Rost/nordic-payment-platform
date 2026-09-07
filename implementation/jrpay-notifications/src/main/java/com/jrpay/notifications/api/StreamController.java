package com.jrpay.notifications.api;

import com.jrpay.notifications.config.AuthenticatedUser;
import com.jrpay.notifications.sse.NotificationEventBus;
import com.jrpay.notifications.sse.StreamTokenService;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1/customer/notifications")
public class StreamController {

    private static final String STREAM_PURPOSE = "notification-stream";

    private final StreamTokenService streamTokens;
    private final NotificationEventBus bus;
    private final AuthenticatedUser caller;
    private final long streamTimeoutMs;

    public StreamController(StreamTokenService streamTokens,
                            NotificationEventBus bus,
                            AuthenticatedUser caller,
                            @Value("${jrpay.notifications.sse.idle-timeout:PT10M}") Duration timeout) {
        this.streamTokens = streamTokens;
        this.bus = bus;
        this.caller = caller;
        this.streamTimeoutMs = timeout.toMillis();
    }

    @PostMapping("/stream-token")
    public Map<String, Object> streamToken() {
        UUID userId = caller.requireUserId();
        var issued = streamTokens.issue(userId, caller.currentAudience(), STREAM_PURPOSE);
        return Map.of("streamToken", issued.token(), "expiresIn", issued.expiresInSeconds());
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> stream(@RequestParam("token") String token) {
        var consumed = streamTokens.consume(token, STREAM_PURPOSE).orElse(null);
        if (consumed == null) {
            SseEmitter rejected = new SseEmitter(0L);
            rejected.complete();
            return ResponseEntity.status(401).body(rejected);
        }
        SseEmitter emitter = bus.subscribe(consumed.getUserId(), streamTimeoutMs);
        try {
            emitter.send(SseEmitter.event()
                    .name("notification.connected")
                    .data(Map.of("userId", consumed.getUserId())));
        } catch (Exception ignored) {
            // First send failed — emitter will be removed on close.
        }
        return ResponseEntity.ok(emitter);
    }
}
