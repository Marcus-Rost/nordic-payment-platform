package com.jrpay.ledger.admin.api;

import com.jrpay.ledger.admin.AdminAccountService;
import com.jrpay.ledger.admin.AdminProblemException;
import com.jrpay.ledger.admin.dto.AdminAccountDetail;
import com.jrpay.ledger.admin.dto.AdminTransactionSummary;
import com.jrpay.ledger.admin.dto.CloseAccountRequest;
import com.jrpay.ledger.admin.dto.CreateAccountRequest;
import com.jrpay.ledger.admin.dto.FreezeRequest;
import com.jrpay.ledger.admin.dto.SearchResult;
import com.jrpay.ledger.admin.dto.UnfreezeRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * Realises the admin-accounts.yaml surface (UC-G35).
 */
@RestController
@RequestMapping("/api/v1/admin/accounts")
@Validated
public class AdminAccountController {

    private final AdminAccountService service;

    public AdminAccountController(AdminAccountService service) {
        this.service = service;
    }

    @GetMapping("/search")
    @PreAuthorize("hasAuthority('SCOPE_accounts:admin:read')")
    public ResponseEntity<SearchResult> search(
            @RequestParam @NotBlank String mode,
            @RequestParam @NotBlank String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return ResponseEntity.ok(service.search(mode, q, page, size));
    }

    @GetMapping("/{accountId}")
    @PreAuthorize("hasAuthority('SCOPE_accounts:admin:read')")
    public ResponseEntity<AdminAccountDetail> detail(@PathVariable String accountId) {
        return ResponseEntity.ok(service.detail(accountId));
    }

    @GetMapping("/{accountId}/transactions")
    @PreAuthorize("hasAuthority('SCOPE_accounts:admin:read')")
    public ResponseEntity<Map<String, Object>> transactions(
            @PathVariable String accountId,
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        Instant fromI = from == null ? null : from.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant toI = to == null ? null : to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        Page<?> page0 = service.listTransactions(accountId, fromI, toI, PageRequest.of(page, size));
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("items", page0.map(t -> service.toTransactionSummary(
                (com.jrpay.ledger.projection.AccountTransactionEntity) t)).getContent());
        resp.put("page", Map.of(
                "number", page0.getNumber(),
                "size", page0.getSize(),
                "totalElements", page0.getTotalElements(),
                "totalPages", page0.getTotalPages()));
        return ResponseEntity.ok(resp);
    }

    @GetMapping("/{accountId}/audit")
    @PreAuthorize("hasAuthority('SCOPE_accounts:admin:read') and hasAuthority('SCOPE_audit:read')")
    public ResponseEntity<Map<String, Object>> audit(@PathVariable String accountId,
                                                     @RequestParam(required = false) Instant from,
                                                     @RequestParam(required = false) Instant to,
                                                     @RequestParam(defaultValue = "0") int page,
                                                     @RequestParam(defaultValue = "25") int size) {
        // The audit subset is served by jrpay-audit-query. A1 surfaces a thin
        // pointer + a hint to the caller that they should call the audit
        // service directly. The fully-realised version (Phase 2N) federates.
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", java.util.List.of());
        body.put("page", Map.of(
                "number", page,
                "size", size,
                "totalElements", 0,
                "totalPages", 0));
        body.put("federatedFrom", "jrpay-audit-query");
        body.put("hint", "Call /api/v1/admin/audit/events?resourceType=account&resourceId=" + accountId
                + " for the full hash-chained audit subset");
        return ResponseEntity.ok(body);
    }

    @PostMapping("/{accountId}/freeze")
    @PreAuthorize("hasAuthority('SCOPE_accounts:admin:write')")
    public ResponseEntity<AdminAccountDetail> freeze(
            @PathVariable String accountId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "Step-Up-Token", required = false) String stepUpToken,
            @Valid @RequestBody FreezeRequest req) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw AdminProblemException.validation("Idempotency-Key header required");
        }
        return ResponseEntity.ok(service.freeze(accountId, req, stepUpToken));
    }

    @PostMapping("/{accountId}/unfreeze")
    @PreAuthorize("hasAuthority('SCOPE_accounts:admin:write')")
    public ResponseEntity<AdminAccountDetail> unfreeze(
            @PathVariable String accountId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "Step-Up-Token", required = false) String stepUpToken,
            @Valid @RequestBody UnfreezeRequest req) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw AdminProblemException.validation("Idempotency-Key header required");
        }
        return ResponseEntity.ok(service.unfreeze(accountId, req, stepUpToken));
    }

    @PostMapping("/{accountId}/close")
    @PreAuthorize("hasAuthority('SCOPE_accounts:admin:write')")
    public ResponseEntity<AdminAccountDetail> close(
            @PathVariable String accountId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "Step-Up-Token", required = false) String stepUpToken,
            @Valid @RequestBody CloseAccountRequest req) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw AdminProblemException.validation("Idempotency-Key header required");
        }
        return ResponseEntity.ok(service.close(accountId, req, stepUpToken));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('SCOPE_accounts:admin:write')")
    public ResponseEntity<AdminAccountDetail> create(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "Step-Up-Token", required = false) String stepUpToken,
            @Valid @RequestBody CreateAccountRequest req) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw AdminProblemException.validation("Idempotency-Key header required");
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(req, stepUpToken));
    }
}
