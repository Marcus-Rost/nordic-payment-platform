package com.jrpay.psd2.customer.payments;

import com.jrpay.psd2.customer.CustomerProblemException;
import com.jrpay.psd2.customer.payments.dto.PaymentPage;
import com.jrpay.psd2.customer.payments.dto.PaymentRecordDto;
import com.jrpay.psd2.customer.payments.dto.PaymentRequestDto;
import com.jrpay.psd2.customer.payments.vop.VoPClient;
import com.jrpay.psd2.customer.payments.vop.VoPService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/customer/payments")
public class CustomerPaymentController {

    private final CustomerPaymentService service;
    private final VoPService vopService;

    public CustomerPaymentController(CustomerPaymentService service, VoPService vopService) {
        this.service = service;
        this.vopService = vopService;
    }

    @PostMapping("/vop")
    @PreAuthorize("hasAuthority('SCOPE_payments:initiate')")
    public ResponseEntity<Map<String, Object>> verifyPayee(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody VoPRequest body) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw CustomerProblemException.validation("Idempotency-Key header required");
        }
        VoPClient.Result result = vopService.check(body.beneficiaryIban(), body.statedName());
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("result", result.result());
        if (result.actualName() != null && "CLOSE_MATCH".equals(result.result())) {
            resp.put("actualName", result.actualName());
        }
        return ResponseEntity.ok(resp);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('SCOPE_payments:initiate')")
    public ResponseEntity<?> initiate(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "Step-Up-Token", required = false) String stepUpToken,
            @Valid @RequestBody PaymentRequestDto body) {
        var outcome = service.initiate(body, idempotencyKey, stepUpToken);
        return switch (outcome) {
            case CustomerPaymentService.InitiateOutcome.Granted g ->
                    ResponseEntity.ok(g.ack());
            case CustomerPaymentService.InitiateOutcome.PendingSca p ->
                    ResponseEntity.status(HttpStatus.ACCEPTED).body(p.pending());
        };
    }

    @GetMapping("/{paymentId}")
    @PreAuthorize("hasAuthority('SCOPE_payments:read')")
    public ResponseEntity<PaymentRecordDto> getPayment(@PathVariable @NotBlank String paymentId) {
        return ResponseEntity.ok(service.getPayment(paymentId));
    }

    @GetMapping
    @PreAuthorize("hasAuthority('SCOPE_payments:read')")
    public ResponseEntity<PaymentPage> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return ResponseEntity.ok(service.list(page, size));
    }

    @DeleteMapping("/{paymentId}")
    @PreAuthorize("hasAuthority('SCOPE_payments:cancel')")
    public ResponseEntity<Void> cancelPayment(
            @PathVariable @NotBlank String paymentId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw CustomerProblemException.validation("Idempotency-Key header required");
        }
        service.cancelPreSca(paymentId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{paymentId}/submit")
    @PreAuthorize("hasAuthority('SCOPE_payments:initiate')")
    public ResponseEntity<?> submitPayment(
            @PathVariable @NotBlank String paymentId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "Step-Up-Token", required = false) String stepUpToken) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw CustomerProblemException.validation("Idempotency-Key header required");
        }
        return ResponseEntity.ok(service.submit(paymentId, stepUpToken));
    }

    @PostMapping("/{paymentId}/refresh-status")
    @PreAuthorize("hasAuthority('SCOPE_payments:read')")
    public ResponseEntity<PaymentRecordDto> refreshStatus(
            @PathVariable @NotBlank String paymentId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw CustomerProblemException.validation("Idempotency-Key header required");
        }
        return ResponseEntity.ok(service.refreshStatus(paymentId));
    }

    public record VoPRequest(
            @NotBlank String beneficiaryIban,
            @NotBlank String statedName,
            String requestId
    ) {}
}
