package com.jrpay.billpay.customer.mandates;

import com.jrpay.billpay.customer.CustomerProblemException;
import com.jrpay.billpay.customer.mandates.dto.MandateCreateRequest;
import com.jrpay.billpay.customer.mandates.dto.MandateDetail;
import com.jrpay.billpay.customer.mandates.dto.MandateExecutionPage;
import com.jrpay.billpay.customer.mandates.dto.MandatePage;
import com.jrpay.billpay.customer.mandates.dto.MandateUpdateRequest;
import com.jrpay.billpay.customer.mandates.dto.MerchantDto;
import com.jrpay.billpay.customer.mandates.dto.MerchantPage;
import com.jrpay.billpay.domain.Mandate;
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
@RequestMapping("/api/v1/customer/mandates")
public class MandateController {

    private final MandateService mandateService;
    private final MerchantService merchantService;

    public MandateController(MandateService mandateService, MerchantService merchantService) {
        this.mandateService = mandateService;
        this.merchantService = merchantService;
    }

    @GetMapping("/merchants")
    @PreAuthorize("hasAuthority('SCOPE_mandates:manage')")
    public ResponseEntity<MerchantPage> searchMerchants(
            @RequestParam @NotBlank String q,
            @RequestParam(defaultValue = "25") int size) {
        return ResponseEntity.ok(merchantService.search(q, size));
    }

    @GetMapping("/merchants/{merchantId}")
    @PreAuthorize("hasAuthority('SCOPE_mandates:manage')")
    public ResponseEntity<MerchantDto> getMerchant(@PathVariable @NotBlank String merchantId) {
        return ResponseEntity.ok(merchantService.get(merchantId));
    }

    @GetMapping
    @PreAuthorize("hasAuthority('SCOPE_mandates:manage')")
    public ResponseEntity<MandatePage> list(
            @RequestParam(required = false) List<String> status,
            @RequestParam(required = false) String merchantQuery,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return ResponseEntity.ok(mandateService.list(parseStatuses(status), merchantQuery, page, size));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('SCOPE_mandates:manage')")
    public ResponseEntity<?> create(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody MandateCreateRequest body) {
        requireIdempotencyKey(idempotencyKey);
        var outcome = mandateService.create(body);
        return ResponseEntity.status(HttpStatus.CREATED).body(outcome.pending());
    }

    @GetMapping("/{mandateId}")
    @PreAuthorize("hasAuthority('SCOPE_mandates:manage')")
    public ResponseEntity<MandateDetail> detail(@PathVariable @NotBlank String mandateId) {
        return ResponseEntity.ok(mandateService.detail(mandateId));
    }

    @PatchMapping("/{mandateId}")
    @PreAuthorize("hasAuthority('SCOPE_mandates:manage')")
    public ResponseEntity<?> update(
            @PathVariable @NotBlank String mandateId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "Step-Up-Token", required = false) String stepUpToken,
            @Valid @RequestBody MandateUpdateRequest body) {
        requireIdempotencyKey(idempotencyKey);
        var outcome = mandateService.update(mandateId, body, stepUpToken);
        return switch (outcome) {
            case MandateService.UpdateOutcome.Granted g -> ResponseEntity.ok(g.detail());
            case MandateService.UpdateOutcome.PendingSca p ->
                    ResponseEntity.status(HttpStatus.ACCEPTED).body(p.pending());
        };
    }

    @DeleteMapping("/{mandateId}")
    @PreAuthorize("hasAuthority('SCOPE_mandates:manage')")
    public ResponseEntity<MandateDetail> revoke(
            @PathVariable @NotBlank String mandateId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        requireIdempotencyKey(idempotencyKey);
        return ResponseEntity.ok(mandateService.revoke(mandateId));
    }

    @GetMapping("/{mandateId}/executions")
    @PreAuthorize("hasAuthority('SCOPE_mandates:manage')")
    public ResponseEntity<MandateExecutionPage> executions(
            @PathVariable @NotBlank String mandateId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return ResponseEntity.ok(mandateService.executions(mandateId, page, size));
    }

    private static void requireIdempotencyKey(String key) {
        if (key == null || key.isBlank()) {
            throw CustomerProblemException.validation("Idempotency-Key header required");
        }
    }

    private static Collection<Mandate.MandateStatus> parseStatuses(List<String> raw) {
        if (raw == null || raw.isEmpty()) return List.of();
        List<Mandate.MandateStatus> out = new ArrayList<>();
        for (String s : raw) {
            try {
                out.add(Mandate.MandateStatus.valueOf(s));
            } catch (IllegalArgumentException e) {
                throw CustomerProblemException.invalidInput("Unknown mandate status: " + s);
            }
        }
        return out;
    }
}
