package com.jrpay.psd2.customer.accounts;

import com.jrpay.psd2.customer.CustomerProblemException;
import com.jrpay.psd2.customer.accounts.dto.AccountDetailDto;
import com.jrpay.psd2.customer.accounts.dto.AccountSummaryDto;
import com.jrpay.psd2.customer.accounts.dto.BalancesDto;
import com.jrpay.psd2.customer.accounts.dto.StatementRequestDto;
import com.jrpay.psd2.customer.accounts.dto.StatementResponseDto;
import com.jrpay.psd2.customer.accounts.dto.TransactionDetailDto;
import com.jrpay.psd2.customer.accounts.dto.TransactionPage;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/customer")
public class CustomerAccountController {

    private final CustomerAccountService service;

    public CustomerAccountController(CustomerAccountService service) {
        this.service = service;
    }

    @GetMapping("/accounts")
    @PreAuthorize("hasAuthority('SCOPE_accounts:read')")
    public ResponseEntity<List<AccountSummaryDto>> listAccounts() {
        return ResponseEntity.ok(service.list());
    }

    @GetMapping("/accounts/{accountId}")
    @PreAuthorize("hasAuthority('SCOPE_accounts:read')")
    public ResponseEntity<AccountDetailDto> getAccount(@PathVariable @NotBlank String accountId) {
        return ResponseEntity.ok(service.detail(accountId));
    }

    @GetMapping("/accounts/{accountId}/balances")
    @PreAuthorize("hasAuthority('SCOPE_accounts:read')")
    public ResponseEntity<BalancesDto> getAccountBalances(@PathVariable @NotBlank String accountId) {
        return ResponseEntity.ok(service.balances(accountId));
    }

    @GetMapping("/accounts/{accountId}/transactions")
    @PreAuthorize("hasAuthority('SCOPE_transactions:read')")
    public ResponseEntity<TransactionPage> listTransactions(
            @PathVariable @NotBlank String accountId,
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return ResponseEntity.ok(service.listTransactions(accountId, from, to, page, size));
    }

    @GetMapping("/transactions/{transactionId}")
    @PreAuthorize("hasAuthority('SCOPE_transactions:read')")
    public ResponseEntity<TransactionDetailDto> getTransaction(
            @PathVariable @NotBlank String transactionId) {
        return ResponseEntity.ok(service.getTransaction(transactionId));
    }

    @GetMapping("/transactions/{transactionId}/receipt.pdf")
    @PreAuthorize("hasAuthority('SCOPE_transactions:read')")
    public ResponseEntity<Void> getTransactionReceipt(@PathVariable @NotBlank String transactionId) {
        String url = service.getTransactionReceiptSignedUrl(transactionId);
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(url))
                .build();
    }

    @PostMapping("/accounts/{accountId}/statements")
    @PreAuthorize("hasAuthority('SCOPE_transactions:read')")
    public ResponseEntity<StatementResponseDto> requestStatement(
            @PathVariable @NotBlank String accountId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody StatementRequestDto body) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw CustomerProblemException.validation("Idempotency-Key header required");
        }
        return ResponseEntity.ok(service.requestStatement(accountId, body));
    }

    @GetMapping("/statements/{requestId}")
    @PreAuthorize("hasAuthority('SCOPE_transactions:read')")
    public ResponseEntity<StatementResponseDto> getStatement(@PathVariable UUID requestId) {
        return ResponseEntity.ok(service.getStatement(requestId));
    }
}
