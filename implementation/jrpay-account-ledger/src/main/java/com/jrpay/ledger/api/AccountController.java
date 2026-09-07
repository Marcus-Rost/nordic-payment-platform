package com.jrpay.ledger.api;

import com.jrpay.domain.money.Currency;
import com.jrpay.domain.money.MonetaryAmount;
import com.jrpay.ledger.command.AccountCommandHandler;
import com.jrpay.ledger.projection.AccountEntity;
import com.jrpay.ledger.projection.AccountRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/accounts")
public class AccountController {

    private final AccountRepository accountRepository;
    private final AccountCommandHandler commandHandler;

    public AccountController(AccountRepository accountRepository,
                             AccountCommandHandler commandHandler) {
        this.accountRepository = accountRepository;
        this.commandHandler = commandHandler;
    }

    @GetMapping("/{accountId}")
    public ResponseEntity<AccountResponse> getAccount(@PathVariable String accountId) {
        return accountRepository.findByAccountId(accountId)
                .map(e -> ResponseEntity.ok(AccountResponse.from(e)))
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{accountId}/balances")
    public ResponseEntity<BalanceResponse> getBalances(@PathVariable String accountId) {
        return accountRepository.findByAccountId(accountId)
                .map(e -> ResponseEntity.ok(new BalanceResponse(
                        e.getAccountId(),
                        e.getBookedBalance(),
                        e.getReservedAmount(),
                        e.getAvailableBalance(),
                        e.getCurrency()
                )))
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{accountId}/holder-name")
    public ResponseEntity<HolderNameResponse> getHolderName(@PathVariable String accountId) {
        return accountRepository.findByAccountId(accountId)
                .map(e -> ResponseEntity.ok(new HolderNameResponse(
                        e.getAccountId(),
                        e.getHolderName(),
                        e.getUpdatedAt().toString()
                )))
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<AccountResponse> createAccount(@Valid @RequestBody CreateAccountRequest request) {
        Currency currency = Currency.fromCode(request.currency());
        MonetaryAmount initialBalance = MonetaryAmount.of(request.initialBalance().toString(), currency);

        AccountEntity account = commandHandler.createAccount(
                request.accountId(), request.holderName(), currency, initialBalance);

        return ResponseEntity.status(HttpStatus.CREATED).body(AccountResponse.from(account));
    }

    /**
     * Execute a payment (double-entry: debit debtor, credit creditor).
     * In production, this would go through A2 clearing. For demo, direct execution.
     */
    @PostMapping("/transfer")
    public ResponseEntity<TransferResponse> transfer(@Valid @RequestBody TransferRequest request) {
        Currency currency = Currency.fromCode(request.currency());
        MonetaryAmount amount = MonetaryAmount.of(request.amount().toString(), currency);

        commandHandler.applyDoubleEntry(
                request.transactionId(), request.debtorAccountId(),
                request.creditorAccountId(), amount, request.description());

        AccountEntity debtor = accountRepository.findByAccountId(request.debtorAccountId()).orElseThrow();
        AccountEntity creditor = accountRepository.findByAccountId(request.creditorAccountId()).orElseThrow();

        return ResponseEntity.ok(new TransferResponse(
                request.transactionId(), "SETTLED",
                AccountResponse.from(debtor), AccountResponse.from(creditor)));
    }

    // DTOs
    record CreateAccountRequest(
            @NotBlank String accountId,
            @NotBlank String holderName,
            @NotBlank String currency,
            @NotNull @Positive BigDecimal initialBalance
    ) {}

    record AccountResponse(
            String accountId, String holderName, String currency,
            BigDecimal bookedBalance, BigDecimal reservedAmount,
            BigDecimal availableBalance, String status
    ) {
        static AccountResponse from(AccountEntity e) {
            return new AccountResponse(
                    e.getAccountId(), e.getHolderName(), e.getCurrency(),
                    e.getBookedBalance(), e.getReservedAmount(),
                    e.getAvailableBalance(), e.getStatus().name()
            );
        }
    }

    record BalanceResponse(String accountId, BigDecimal bookedBalance,
                           BigDecimal reservedAmount, BigDecimal availableBalance,
                           String currency) {}

    record HolderNameResponse(String accountId, String holderName, String lastUpdated) {}

    record TransferRequest(
            @NotBlank String transactionId,
            @NotBlank String debtorAccountId,
            @NotBlank String creditorAccountId,
            @NotNull @Positive BigDecimal amount,
            @NotBlank String currency,
            String description
    ) {}

    record TransferResponse(
            String transactionId, String status,
            AccountResponse debtor, AccountResponse creditor
    ) {}
}
