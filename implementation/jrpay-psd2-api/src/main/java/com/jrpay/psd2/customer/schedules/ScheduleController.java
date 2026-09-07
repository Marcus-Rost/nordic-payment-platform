package com.jrpay.psd2.customer.schedules;

import com.jrpay.psd2.customer.CustomerProblemException;
import com.jrpay.psd2.customer.domain.PaymentScheduleEntity;
import com.jrpay.psd2.customer.schedules.dto.ScheduleDto;
import com.jrpay.psd2.customer.schedules.dto.SchedulePage;
import com.jrpay.psd2.customer.schedules.dto.ScheduleRequestDto;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/customer/payments/schedules")
public class ScheduleController {

    private final ScheduleService service;

    public ScheduleController(ScheduleService service) {
        this.service = service;
    }

    @PostMapping
    @PreAuthorize("hasAuthority('SCOPE_payments:initiate')")
    public ResponseEntity<?> create(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "Step-Up-Token", required = false) String stepUpToken,
            @Valid @RequestBody ScheduleRequestDto body) {
        var outcome = service.create(body, idempotencyKey, stepUpToken);
        return switch (outcome) {
            case ScheduleService.CreateOutcome.Granted g ->
                    ResponseEntity.status(HttpStatus.CREATED).body(g.schedule());
            case ScheduleService.CreateOutcome.PendingSca p ->
                    ResponseEntity.status(HttpStatus.ACCEPTED).body(p.pending());
        };
    }

    @GetMapping
    @PreAuthorize("hasAuthority('SCOPE_payments:read')")
    public ResponseEntity<SchedulePage> list(
            @RequestParam(required = false) List<String> status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return ResponseEntity.ok(service.list(parseStatuses(status), page, size));
    }

    @GetMapping("/{scheduleId}")
    @PreAuthorize("hasAuthority('SCOPE_payments:read')")
    public ResponseEntity<ScheduleDto> get(@PathVariable @NotBlank String scheduleId) {
        return ResponseEntity.ok(service.get(scheduleId));
    }

    @DeleteMapping("/{scheduleId}")
    @PreAuthorize("hasAuthority('SCOPE_payments:cancel')")
    public ResponseEntity<ScheduleDto> cancel(
            @PathVariable @NotBlank String scheduleId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw CustomerProblemException.validation("Idempotency-Key header required");
        }
        return ResponseEntity.ok(service.cancel(scheduleId));
    }

    private static Collection<PaymentScheduleEntity.Status> parseStatuses(List<String> raw) {
        if (raw == null || raw.isEmpty()) return List.of();
        List<PaymentScheduleEntity.Status> out = new ArrayList<>();
        for (String s : raw) {
            try {
                out.add(PaymentScheduleEntity.Status.valueOf(s));
            } catch (IllegalArgumentException e) {
                throw CustomerProblemException.invalidInput("Unknown schedule status: " + s);
            }
        }
        return out;
    }
}
