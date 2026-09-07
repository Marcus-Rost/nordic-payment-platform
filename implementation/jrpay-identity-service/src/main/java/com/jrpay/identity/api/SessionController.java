package com.jrpay.identity.api;

import com.jrpay.identity.config.AuthenticatedUser;
import com.jrpay.identity.domain.IdentitySession;
import com.jrpay.identity.domain.IdentityUser;
import com.jrpay.identity.repository.IdentitySessionRepository;
import com.jrpay.identity.repository.IdentityUserRepository;
import com.jrpay.identity.session.StreamTokenService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * UC-G52 / UC-G53 customer session-management surface.
 *
 * <p>{@code stream-token} mints a one-time SSE token; {@code stream} accepts
 * the token and emits {@code session.warning} (T-60s idle warning) +
 * {@code session.revoked} events. {@code active} lists every live device
 * session; the per-session DELETE revokes one.
 *
 * <p>For the portfolio default the SSE stream is a heartbeat-only emitter —
 * production will subscribe to the session-store change feed. The endpoint
 * shape matches the spec so client GUIs can integrate now.
 */
@RestController
@RequestMapping("/api/v1/customer/session")
public class SessionController {

    private final IdentitySessionRepository sessions;
    private final IdentityUserRepository users;
    private final StreamTokenService streamTokens;
    private final AuthenticatedUser caller;

    public SessionController(IdentitySessionRepository sessions,
                             IdentityUserRepository users,
                             StreamTokenService streamTokens,
                             AuthenticatedUser caller) {
        this.sessions = sessions;
        this.users = users;
        this.streamTokens = streamTokens;
        this.caller = caller;
    }

    @GetMapping("/active")
    public List<Map<String, Object>> active() {
        UUID userId = caller.requireUserId();
        return sessions.findByUserIdAndRevokedAtIsNullOrderByLastSeenAtDesc(userId).stream()
                .map(this::sessionView)
                .toList();
    }

    @DeleteMapping("/{sessionId}")
    public ResponseEntity<Void> revoke(@PathVariable UUID sessionId) {
        UUID userId = caller.requireUserId();
        IdentitySession session = sessions.findById(sessionId).orElse(null);
        if (session == null || !session.getUserId().equals(userId)) {
            return ResponseEntity.notFound().build();
        }
        if (session.isActive()) {
            session.revoke("CUSTOMER_REQUEST");
            sessions.save(session);
        }
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/stream-token")
    public Map<String, Object> streamToken() {
        UUID userId = caller.requireUserId();
        IdentityUser user = users.getReferenceById(userId);
        var issued = streamTokens.issue(userId, user.getAudience(), "session-stream");
        return Map.of(
                "streamToken", issued.token(),
                "expiresIn", issued.expiresInSeconds()
        );
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam("token") String token) {
        var consumed = streamTokens.consume(token, "session-stream").orElse(null);
        SseEmitter emitter = new SseEmitter(java.time.Duration.ofMinutes(10).toMillis());
        if (consumed == null) {
            try {
                emitter.send(SseEmitter.event()
                        .name("error")
                        .data(Map.of("type", "urn:problem:signed-url-expired")));
            } catch (Exception ignored) { /* fall through */ }
            emitter.complete();
            return emitter;
        }
        try {
            emitter.send(SseEmitter.event()
                    .name("session.connected")
                    .data(Map.of("userId", consumed.getUserId())));
        } catch (Exception e) {
            emitter.completeWithError(e);
        }
        // Heartbeat hook — production attaches to the session-store change feed.
        return emitter;
    }

    private Map<String, Object> sessionView(IdentitySession s) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("sessionId", s.getSessionId());
        v.put("deviceName", s.getDeviceName());
        v.put("userAgent", s.getUserAgent());
        v.put("lastSeenAt", s.getLastSeenAt());
        v.put("current", false);
        if (s.getIpCountry() != null) v.put("ipCountry", s.getIpCountry());
        return v;
    }
}
