package com.jrpay.psd2.customer.payments.vop;

import com.jrpay.psd2.customer.CustomerProblemException;
import com.jrpay.psd2.customer.IbanValidator;
import com.jrpay.psd2.customer.audit.CustomerAuditEvent;
import com.jrpay.psd2.customer.audit.CustomerAuditPublisher;
import com.jrpay.psd2.customer.config.AuthenticatedUser;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Per-customer VoP rate limiter (sliding 60-s window, 30 req/min). Anti-
 * enumeration is enforced by the underlying A6 service (per-IP timing floor).
 */
@Service
public class VoPService {

    private final VoPClient client;
    private final AuthenticatedUser caller;
    private final CustomerAuditPublisher audit;
    private final int maxPerWindow;
    private final long windowSeconds;

    private final ConcurrentHashMap<UUID, Window> windows = new ConcurrentHashMap<>();

    public VoPService(VoPClient client, AuthenticatedUser caller,
                      CustomerAuditPublisher audit,
                      @Value("${jrpay.psd2.vop.max-per-window:30}") int maxPerWindow,
                      @Value("${jrpay.psd2.vop.window-seconds:60}") long windowSeconds) {
        this.client = client;
        this.caller = caller;
        this.audit = audit;
        this.maxPerWindow = maxPerWindow;
        this.windowSeconds = windowSeconds;
    }

    public VoPClient.Result check(String iban, String statedName) {
        UUID uid = caller.requireUserId();
        if (!IbanValidator.isValid(iban)) {
            throw CustomerProblemException.invalidIban("IBAN failed mod-97 check");
        }
        guardRateLimit(uid);

        VoPClient.Result result = client.check(IbanValidator.compact(iban), statedName);

        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("ibanMasked", IbanValidator.mask(IbanValidator.compact(iban)));
        payload.put("result", result.result());
        // Audit body never carries actualName per FG.4.
        audit.publish(CustomerAuditEvent.builder("payment.vop")
                .actor(caller.currentSubject())
                .customerUserId(uid)
                .resourceType("vop")
                .resourceId(IbanValidator.compact(iban))
                .summary("VoP check → " + result.result())
                .payload(payload)
                .build());

        return result;
    }

    private void guardRateLimit(UUID uid) {
        Instant now = Instant.now();
        Instant windowStart = now.minus(windowSeconds, ChronoUnit.SECONDS);
        windows.compute(uid, (k, w) -> {
            if (w == null || w.start.isBefore(windowStart)) {
                return new Window(now, new AtomicInteger(1));
            }
            int next = w.count.incrementAndGet();
            if (next > maxPerWindow) {
                throw CustomerProblemException.rateLimited(
                        "VoP rate limit (" + maxPerWindow + "/" + windowSeconds + "s) exceeded");
            }
            return w;
        });
    }

    private record Window(Instant start, AtomicInteger count) { }
}
