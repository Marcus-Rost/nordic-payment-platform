package com.jrpay.ledger.admin.api;

import com.jrpay.ledger.admin.AdminProblemException;
import com.jrpay.ledger.admin.PiiRevealService;
import com.jrpay.ledger.admin.dto.RevealPiiAck;
import com.jrpay.ledger.admin.dto.RevealPiiExtendRequest;
import com.jrpay.ledger.admin.dto.RevealPiiRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/accounts/{accountId}/reveal-pii")
public class PiiRevealController {

    private final PiiRevealService reveals;

    public PiiRevealController(PiiRevealService reveals) {
        this.reveals = reveals;
    }

    @PostMapping
    @PreAuthorize("hasAuthority('SCOPE_accounts:admin:read')")
    public ResponseEntity<?> reveal(
            @PathVariable String accountId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "Step-Up-Token", required = false) String stepUpToken,
            @Valid @RequestBody RevealPiiRequest req) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw AdminProblemException.validation("Idempotency-Key header required");
        }
        PiiRevealService.RevealOutcome outcome = reveals.reveal(accountId, req, stepUpToken);
        return switch (outcome) {
            case PiiRevealService.RevealOutcome.Granted g ->
                    ResponseEntity.ok(g.ack());
            case PiiRevealService.RevealOutcome.PendingApproval p ->
                    ResponseEntity.accepted().body(p.response());
        };
    }

    @PostMapping("/extend")
    @PreAuthorize("hasAuthority('SCOPE_accounts:admin:read')")
    public ResponseEntity<Map<String, Object>> extend(
            @PathVariable String accountId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "Step-Up-Token", required = false) String stepUpToken,
            @Valid @RequestBody RevealPiiExtendRequest req) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw AdminProblemException.validation("Idempotency-Key header required");
        }
        return ResponseEntity.ok(reveals.extend(accountId, req, stepUpToken));
    }

    @PostMapping("/approve")
    @PreAuthorize("hasAuthority('SCOPE_accounts:admin:write')")
    public ResponseEntity<RevealPiiAck> approve(
            @PathVariable String accountId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "Step-Up-Token", required = false) String stepUpToken,
            @Valid @RequestBody ApproveRequest body) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw AdminProblemException.validation("Idempotency-Key header required");
        }
        return ResponseEntity.ok(reveals.approve(accountId, body.approvalRef(), stepUpToken));
    }

    public record ApproveRequest(@NotBlank String approvalRef) {}
}
