package com.jrpay.psd2.customer.payments.stream;

import com.jrpay.psd2.customer.CustomerProblemException;
import jakarta.validation.Valid;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
public class PaymentStreamController {

    private final StreamTokenService tokens;
    private final PaymentStreamHub hub;

    public PaymentStreamController(StreamTokenService tokens, PaymentStreamHub hub) {
        this.tokens = tokens;
        this.hub = hub;
    }

    @PostMapping("/api/v1/customer/payments/stream-token")
    @PreAuthorize("hasAuthority('SCOPE_payments:read')")
    public ResponseEntity<Map<String, Object>> mintToken(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody(required = false) StreamTokenRequest body) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw CustomerProblemException.validation("Idempotency-Key header required");
        }
        String paymentScope = body == null ? null : body.paymentId();
        var issued = tokens.mint(paymentScope);
        return ResponseEntity.status(HttpStatus.OK).body(Map.of(
                "streamToken", issued.token(),
                "expiresIn", issued.expiresInSeconds()));
    }

    @GetMapping(path = "/api/v1/customer/payments/stream",
                produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam("token") String token,
                             @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
        UUID uid = tokens.consume(token)
                .orElseThrow(StreamTokenService::invalidToken);
        // Client is permitted to pass paymentId scope at mint-time; we pass
        // null here so all events for the customer flow (the hub still
        // honours the scope it stored at mint time).
        SseEmitter emitter = new SseEmitter(0L); // no timeout
        var sub = hub.register(uid, null, emitter);
        try {
            emitter.send(SseEmitter.event().name("connected").data(Map.of(
                    "customerUserId", uid.toString(),
                    "lastEventId", lastEventId == null ? "" : lastEventId)));
        } catch (Exception ignored) { }
        return emitter;
    }

    public record StreamTokenRequest(String paymentId) {}
}
