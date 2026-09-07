package com.jrpay.billpay.customer.beneficiaries;

import com.jrpay.billpay.customer.CustomerProblemException;
import com.jrpay.billpay.customer.beneficiaries.dto.BeneficiaryAddRequest;
import com.jrpay.billpay.customer.beneficiaries.dto.BeneficiaryDetail;
import com.jrpay.billpay.customer.beneficiaries.dto.BeneficiaryPage;
import com.jrpay.billpay.customer.beneficiaries.dto.BeneficiaryPendingSca;
import com.jrpay.billpay.customer.beneficiaries.dto.BeneficiaryUpdateRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/customer/beneficiaries")
public class BeneficiaryController {

    private final BeneficiaryService service;

    public BeneficiaryController(BeneficiaryService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('SCOPE_beneficiaries:manage')")
    public ResponseEntity<BeneficiaryPage> list(
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return ResponseEntity.ok(service.list(q, page, size));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('SCOPE_beneficiaries:manage')")
    public ResponseEntity<BeneficiaryPendingSca> add(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody BeneficiaryAddRequest body) {
        requireIdempotencyKey(idempotencyKey);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(service.add(body));
    }

    @GetMapping("/{beneficiaryId}")
    @PreAuthorize("hasAuthority('SCOPE_beneficiaries:manage')")
    public ResponseEntity<BeneficiaryDetail> detail(@PathVariable @NotBlank String beneficiaryId) {
        return ResponseEntity.ok(service.detail(beneficiaryId));
    }

    @PatchMapping("/{beneficiaryId}")
    @PreAuthorize("hasAuthority('SCOPE_beneficiaries:manage')")
    public ResponseEntity<BeneficiaryDetail> update(
            @PathVariable @NotBlank String beneficiaryId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody BeneficiaryUpdateRequest body) {
        requireIdempotencyKey(idempotencyKey);
        return ResponseEntity.ok(service.update(beneficiaryId, body));
    }

    @DeleteMapping("/{beneficiaryId}")
    @PreAuthorize("hasAuthority('SCOPE_beneficiaries:manage')")
    public ResponseEntity<Void> remove(
            @PathVariable @NotBlank String beneficiaryId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        requireIdempotencyKey(idempotencyKey);
        service.remove(beneficiaryId);
        return ResponseEntity.noContent().build();
    }

    private static void requireIdempotencyKey(String key) {
        if (key == null || key.isBlank()) {
            throw CustomerProblemException.validation("Idempotency-Key header required");
        }
    }
}
