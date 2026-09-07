package com.jrpay.ledger.command;

import com.jrpay.domain.exception.AccountFrozenException;
import com.jrpay.domain.exception.InsufficientBalanceException;
import com.jrpay.domain.exception.SelfTransferException;
import com.jrpay.domain.money.Currency;
import com.jrpay.domain.money.MonetaryAmount;
import com.jrpay.ledger.domain.AccountStatus;
import com.jrpay.ledger.domain.ReservationStatus;
import com.jrpay.ledger.event.*;
import com.jrpay.ledger.projection.AccountEntity;
import com.jrpay.ledger.projection.AccountRepository;
import com.jrpay.ledger.projection.AccountTransactionEntity;
import com.jrpay.ledger.projection.AccountTransactionRepository;
import com.jrpay.ledger.projection.ReservationEntity;
import com.jrpay.ledger.projection.ReservationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Handles account commands by validating against the projection (read model),
 * publishing events to Kafka, and updating the projection.
 *
 * <p>In a full event-sourced implementation, the Kafka publish would be the primary
 * write and the projection update would be async. For this implementation,
 * we do both synchronously for simplicity while maintaining the event-first principle.
 */
@Service
public class AccountCommandHandler {

    private static final Logger log = LoggerFactory.getLogger(AccountCommandHandler.class);

    private final AccountRepository accountRepository;
    private final ReservationRepository reservationRepository;
    private final AccountTransactionRepository transactionRepository;
    private final EventPublisher eventPublisher;

    public AccountCommandHandler(AccountRepository accountRepository,
                                 ReservationRepository reservationRepository,
                                 AccountTransactionRepository transactionRepository,
                                 EventPublisher eventPublisher) {
        this.accountRepository = accountRepository;
        this.reservationRepository = reservationRepository;
        this.transactionRepository = transactionRepository;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Create a new account.
     */
    @Transactional
    public AccountEntity createAccount(String accountId, String holderName,
                                       Currency currency, MonetaryAmount initialBalance) {
        // Idempotency check
        Optional<AccountEntity> existing = accountRepository.findByAccountId(accountId);
        if (existing.isPresent()) {
            log.info("Account {} already exists — idempotent create", accountId);
            return existing.get();
        }

        Instant now = Instant.now();

        // Publish event
        AccountCreatedEvent event = new AccountCreatedEvent(
                UUID.randomUUID().toString(),
                accountId,
                holderName,
                currency.name(),
                initialBalance.toPlainString(),
                now
        );
        eventPublisher.publish(event);

        // Update projection
        AccountEntity entity = new AccountEntity(
                accountId, holderName, currency.name(),
                initialBalance.getAmount(), BigDecimal.ZERO,
                AccountStatus.ACTIVE, now, now
        );
        return accountRepository.save(entity);
    }

    /**
     * Reserve balance for a pending payment.
     */
    @Transactional
    public ReservationEntity reserve(String accountId, String reservationId,
                                     String transactionId, MonetaryAmount amount) {
        // Idempotency: check if reservation already exists
        Optional<ReservationEntity> existing = reservationRepository.findByReservationId(reservationId);
        if (existing.isPresent()) {
            log.info("Reservation {} already exists — idempotent reserve", reservationId);
            return existing.get();
        }

        AccountEntity account = accountRepository.findByAccountId(accountId)
                .orElseThrow(() -> new IllegalArgumentException("Account not found: " + accountId));

        // Validate
        if (account.getStatus() == AccountStatus.FROZEN) {
            throw new AccountFrozenException(accountId);
        }
        if (account.getStatus() == AccountStatus.CLOSED) {
            throw new IllegalStateException("Account " + accountId + " is closed");
        }

        MonetaryAmount available = MonetaryAmount.of(account.getAvailableBalance(),
                Currency.fromCode(account.getCurrency()));
        if (!available.isGreaterThanOrEqual(amount)) {
            throw new InsufficientBalanceException(accountId, available, amount);
        }

        Instant now = Instant.now();

        // Publish event
        BalanceReservedEvent event = new BalanceReservedEvent(
                UUID.randomUUID().toString(),
                accountId, reservationId, transactionId,
                amount.toPlainString(), amount.getCurrency().name(), now
        );
        eventPublisher.publish(event);

        // Update projection
        account.setReservedAmount(account.getReservedAmount().add(amount.getAmount()));
        account.setUpdatedAt(now);
        accountRepository.save(account);

        ReservationEntity reservation = new ReservationEntity(
                reservationId, accountId, transactionId,
                amount.getAmount(), amount.getCurrency().name(),
                ReservationStatus.RESERVED, now
        );
        return reservationRepository.save(reservation);
    }

    /**
     * Release a reservation (payment rejected or timeout).
     * Idempotent — releasing an already-released or confirmed reservation is a no-op.
     */
    @Transactional
    public void release(String reservationId, String reason) {
        Optional<ReservationEntity> opt = reservationRepository.findByReservationId(reservationId);
        if (opt.isEmpty()) {
            log.info("Reservation {} not found — nothing to release", reservationId);
            return;
        }

        ReservationEntity reservation = opt.get();
        if (reservation.getStatus() != ReservationStatus.RESERVED) {
            log.info("Reservation {} already {} — idempotent release",
                    reservationId, reservation.getStatus());
            return;
        }

        Instant now = Instant.now();

        // Publish event
        BalanceReleasedEvent event = new BalanceReleasedEvent(
                UUID.randomUUID().toString(),
                reservation.getAccountId(), reservationId,
                reservation.getTransactionId(), reason, now
        );
        eventPublisher.publish(event);

        // Update projection
        reservation.setStatus(ReservationStatus.RELEASED);
        reservation.setResolvedAt(now);
        reservationRepository.save(reservation);

        AccountEntity account = accountRepository.findByAccountId(reservation.getAccountId())
                .orElseThrow();
        account.setReservedAmount(account.getReservedAmount().subtract(reservation.getAmount()));
        account.setUpdatedAt(now);
        accountRepository.save(account);
    }

    /**
     * Confirm a reservation (settlement applied).
     * Idempotent — confirming an already-confirmed reservation is a no-op.
     * Confirming an already-released reservation is a no-op (crash recovery scenario).
     */
    @Transactional
    public void confirm(String reservationId) {
        Optional<ReservationEntity> opt = reservationRepository.findByReservationId(reservationId);
        if (opt.isEmpty()) {
            log.info("Reservation {} not found — nothing to confirm", reservationId);
            return;
        }

        ReservationEntity reservation = opt.get();
        if (reservation.getStatus() != ReservationStatus.RESERVED) {
            // Bidirectional idempotency: CONFIRM on RELEASED or CONFIRMED is a no-op
            log.info("Reservation {} already {} — idempotent confirm",
                    reservationId, reservation.getStatus());
            return;
        }

        Instant now = Instant.now();

        // Publish event
        BalanceConfirmedEvent event = new BalanceConfirmedEvent(
                UUID.randomUUID().toString(),
                reservation.getAccountId(), reservationId,
                reservation.getTransactionId(), now
        );
        eventPublisher.publish(event);

        // Update projection — remove from reserved (funds are now settled)
        reservation.setStatus(ReservationStatus.CONFIRMED);
        reservation.setResolvedAt(now);
        reservationRepository.save(reservation);

        AccountEntity account = accountRepository.findByAccountId(reservation.getAccountId())
                .orElseThrow();
        account.setReservedAmount(account.getReservedAmount().subtract(reservation.getAmount()));
        account.setUpdatedAt(now);
        accountRepository.save(account);
    }

    /**
     * Freeze an account (regulatory hold — debits rejected, credits allowed).
     */
    @Transactional
    public void freezeAccount(String accountId, String reason) {
        AccountEntity account = accountRepository.findByAccountId(accountId)
                .orElseThrow(() -> new IllegalArgumentException("Account not found: " + accountId));

        if (account.getStatus() == AccountStatus.FROZEN) {
            log.info("Account {} already frozen — idempotent freeze", accountId);
            return;
        }

        Instant now = Instant.now();
        eventPublisher.publish(new AccountFrozenEvent(
                UUID.randomUUID().toString(), accountId, reason, now));

        account.setStatus(AccountStatus.FROZEN);
        account.setUpdatedAt(now);
        accountRepository.save(account);
    }

    /**
     * Unfreeze an account.
     */
    @Transactional
    public void unfreezeAccount(String accountId, String reason) {
        AccountEntity account = accountRepository.findByAccountId(accountId)
                .orElseThrow(() -> new IllegalArgumentException("Account not found: " + accountId));

        if (account.getStatus() == AccountStatus.ACTIVE) {
            log.info("Account {} already active — idempotent unfreeze", accountId);
            return;
        }

        Instant now = Instant.now();
        eventPublisher.publish(new AccountUnfrozenEvent(
                UUID.randomUUID().toString(), accountId, reason, now));

        account.setStatus(AccountStatus.ACTIVE);
        account.setUpdatedAt(now);
        accountRepository.save(account);
    }

    /**
     * Apply a double-entry transaction (debit one account, credit another).
     * The compound event ensures atomicity — both legs in one message.
     *
     * <p>Idempotent on {@code transactionId}: a second invocation with the
     * same id is a no-op (returns without re-applying balances or
     * republishing). The projection's composite PK is {@code (transactionId,
     * accountId)} — checking either leg's existence is sufficient because
     * both are written atomically inside the same transaction. Without this
     * check a retry would silently double-debit the debtor (caught by the
     * Phase-B idempotency regression-trap).
     */
    @Transactional
    public void applyDoubleEntry(String transactionId, String debitAccountId,
                                 String creditAccountId, MonetaryAmount amount,
                                 String description) {
        // Reject self-transfers: meaningless on the ledger (net change = 0)
        // and they would collide on the projection's composite PK
        // (transactionId, accountId), causing the second leg's UPSERT to
        // overwrite the first → projection drift, total credits ≠ total
        // debits in the audit aggregate. Caught by the conservation-of-money
        // check during the 2026-04-27 GUI E2E run.
        if (debitAccountId.equals(creditAccountId)) {
            throw new SelfTransferException(debitAccountId);
        }

        // TxnId dedup: short-circuit if a row with this id already exists
        // for either leg. Any such row means the transaction has already
        // been applied — re-applying would double-debit.
        if (transactionRepository.existsById(
                new AccountTransactionEntity.Pk(transactionId, debitAccountId))
                || transactionRepository.existsById(
                new AccountTransactionEntity.Pk(transactionId, creditAccountId))) {
            return;
        }

        AccountEntity debitAccount = accountRepository.findByAccountId(debitAccountId)
                .orElseThrow(() -> new IllegalArgumentException("Debit account not found: " + debitAccountId));
        AccountEntity creditAccount = accountRepository.findByAccountId(creditAccountId)
                .orElseThrow(() -> new IllegalArgumentException("Credit account not found: " + creditAccountId));

        // Enforce account-status invariants on both legs. The production
        // payment path (reserve / confirm via Kafka) already enforces these;
        // the demo /transfer shortcut bypassed them — caught by the GUI E2E
        // test 2026-04-27 where a FROZEN debtor still settled a transfer.
        if (debitAccount.getStatus() == AccountStatus.FROZEN) {
            throw new AccountFrozenException(debitAccountId);
        }
        if (debitAccount.getStatus() == AccountStatus.CLOSED) {
            throw new IllegalStateException("Debit account " + debitAccountId + " is closed");
        }
        // Banking convention: incoming credits to a FROZEN account ARE
        // allowed (refunds, payroll deposits, government benefits). Only
        // outgoing debits are blocked. CLOSED accounts reject in both
        // directions because the account has been terminated entirely.
        if (creditAccount.getStatus() == AccountStatus.CLOSED) {
            throw new IllegalStateException("Credit account " + creditAccountId + " is closed");
        }
        if (debitAccount.getAvailableBalance().compareTo(amount.getAmount()) < 0) {
            throw new InsufficientBalanceException(debitAccountId,
                    MonetaryAmount.of(debitAccount.getAvailableBalance(),
                            Currency.fromCode(debitAccount.getCurrency())),
                    amount);
        }

        Instant now = Instant.now();

        // Publish compound event
        DoubleEntryEvent event = new DoubleEntryEvent(
                UUID.randomUUID().toString(),
                debitAccountId,
                transactionId,
                debitAccountId, creditAccountId,
                amount.toPlainString(), amount.getCurrency().name(),
                description, now
        );
        eventPublisher.publish(event);

        // Update projections — both legs
        debitAccount.setBookedBalance(debitAccount.getBookedBalance().subtract(amount.getAmount()));
        debitAccount.setUpdatedAt(now);
        accountRepository.save(debitAccount);

        creditAccount.setBookedBalance(creditAccount.getBookedBalance().add(amount.getAmount()));
        creditAccount.setUpdatedAt(now);
        accountRepository.save(creditAccount);

        // Write transaction-history rows for both legs so the admin /
        // customer transaction views surface the movement. The previous
        // version of this method updated balances only; the empty
        // account_transactions table caused the operator drill-in view to
        // show "no transactions" even after a successful transfer
        // (regression caught by the GUI E2E test, 2026-04-27).
        transactionRepository.save(new AccountTransactionEntity(
                transactionId, debitAccountId,
                AccountTransactionEntity.Direction.DEBIT,
                amount.getAmount(), amount.getCurrency().name(), "SETTLED",
                creditAccount.getHolderName(), creditAccountId,
                description, null, now));
        transactionRepository.save(new AccountTransactionEntity(
                transactionId, creditAccountId,
                AccountTransactionEntity.Direction.CREDIT,
                amount.getAmount(), amount.getCurrency().name(), "SETTLED",
                debitAccount.getHolderName(), debitAccountId,
                description, null, now));
    }
}
