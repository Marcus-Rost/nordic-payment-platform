package com.jrpay.billpay.customer.r2p;

import com.jrpay.billpay.customer.CustomerProblemException;
import com.jrpay.billpay.customer.r2p.dto.R2pAcceptRequest;
import com.jrpay.billpay.customer.r2p.dto.R2pAmendRequest;
import com.jrpay.billpay.customer.r2p.dto.R2pDeclineRequest;
import com.jrpay.billpay.customer.r2p.dto.R2pDeferRequest;
import com.jrpay.billpay.customer.r2p.dto.R2pDetail;
import com.jrpay.billpay.customer.r2p.dto.R2pPage;
import com.jrpay.billpay.domain.RequestStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * Customer-facing R2P endpoints (UC-G11..G14).
 */
@RestController
@RequestMapping("/api/v1/customer/r2p")
public class R2pController {

    private final R2pService service;

    public R2pController(R2pService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('SCOPE_payments:read')")
    public ResponseEntity<R2pPage> list(
            @RequestParam(required = false) List<String> status,
            @RequestParam(required = false) String merchantQuery,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        Collection<RequestStatus> statuses = parseStatuses(status);
        return ResponseEntity.ok(service.list(statuses, merchantQuery, page, size));
    }

    @GetMapping("/{requestId}")
    @PreAuthorize("hasAuthority('SCOPE_payments:read')")
    public ResponseEntity<R2pDetail> detail(@PathVariable @NotBlank String requestId) {
        return ResponseEntity.ok(service.detail(requestId));
    }

    @PostMapping("/{requestId}/accept")
    @PreAuthorize("hasAuthority('SCOPE_payments:initiate')")
    public ResponseEntity<?> accept(
            @PathVariable @NotBlank String requestId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "Step-Up-Token", required = false) String stepUpToken,
            @Valid @RequestBody R2pAcceptRequest body) {
        requireIdempotencyKey(idempotencyKey);
        var outcome = service.accept(requestId, body, stepUpToken);
        return switch (outcome) {
            case R2pService.AcceptOutcome.Granted g -> ResponseEntity.ok(g.ack());
            case R2pService.AcceptOutcome.PendingSca p ->
                    ResponseEntity.status(HttpStatus.ACCEPTED).body(p.pending());
        };
    }

    @PostMapping("/{requestId}/decline")
    @PreAuthorize("hasAuthority('SCOPE_payments:initiate')")
    public ResponseEntity<R2pDetail> decline(
            @PathVariable @NotBlank String requestId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody R2pDeclineRequest body) {
        requireIdempotencyKey(idempotencyKey);
        return ResponseEntity.ok(service.decline(requestId, body));
    }

    @PostMapping("/{requestId}/defer")
    @PreAuthorize("hasAuthority('SCOPE_payments:initiate')")
    public ResponseEntity<?> defer(
            @PathVariable @NotBlank String requestId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "Step-Up-Token", required = false) String stepUpToken,
            @Valid @RequestBody R2pDeferRequest body) {
        requireIdempotencyKey(idempotencyKey);
        var outcome = service.defer(requestId, body, stepUpToken);
        return switch (outcome) {
            case R2pService.DeferOutcome.Granted g -> ResponseEntity.ok(g.ack());
            case R2pService.DeferOutcome.PendingSca p ->
                    ResponseEntity.status(HttpStatus.ACCEPTED).body(p.pending());
        };
    }

    @PostMapping("/{requestId}/defer/cancel")
    @PreAuthorize("hasAuthority('SCOPE_payments:cancel')")
    public ResponseEntity<R2pDetail> cancelDefer(
            @PathVariable @NotBlank String requestId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        requireIdempotencyKey(idempotencyKey);
        return ResponseEntity.ok(service.cancelDefer(requestId));
    }

    @PostMapping("/{requestId}/amend")
    @PreAuthorize("hasAuthority('SCOPE_payments:initiate')")
    public ResponseEntity<R2pDetail> amend(
            @PathVariable @NotBlank String requestId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody R2pAmendRequest body) {
        requireIdempotencyKey(idempotencyKey);
        return ResponseEntity.ok(service.amend(requestId, body));
    }

    @PostMapping("/{requestId}/amend/cancel")
    @PreAuthorize("hasAuthority('SCOPE_payments:initiate')")
    public ResponseEntity<R2pDetail> cancelAmend(
            @PathVariable @NotBlank String requestId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        requireIdempotencyKey(idempotencyKey);
        return ResponseEntity.ok(service.cancelAmend(requestId));
    }

    private static void requireIdempotencyKey(String key) {
        if (key == null || key.isBlank()) {
            throw CustomerProblemException.validation("Idempotency-Key header required");
        }
    }

    private static Collection<RequestStatus> parseStatuses(List<String> raw) {
        if (raw == null || raw.isEmpty()) return List.of();
        List<RequestStatus> out = new ArrayList<>();
        for (String s : raw) {
            try {
                out.add(RequestStatus.valueOf(s));
            } catch (IllegalArgumentException e) {
                throw CustomerProblemException.invalidInput("Unknown status: " + s);
            }
        }
        return out;
    }
}
