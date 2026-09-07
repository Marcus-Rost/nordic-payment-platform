package com.jrpay.ledger.projection;

import java.time.Instant;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Account-transaction queries for the admin / customer surface.
 *
 * <p>The four explicit overloads avoid Postgres's "could not determine data
 * type of parameter" error that fires when a single JPQL query has the
 * pattern {@code (:from IS NULL OR posted_at >= :from)}: Postgres sees the
 * same nullable parameter on both sides of the OR and gives up on type
 * inference. Splitting into separate queries with concrete parameter types
 * sidesteps the issue (same workaround as {@code searchInbox} /
 * {@code searchInboxWithQuery} on the casework side).
 */
public interface AccountTransactionRepository
        extends JpaRepository<AccountTransactionEntity, AccountTransactionEntity.Pk> {

    @Query("""
           SELECT t FROM AccountTransactionEntity t
            WHERE t.accountId = :accountId
            ORDER BY t.postedAt DESC
           """)
    Page<AccountTransactionEntity> findAllByAccount(@Param("accountId") String accountId,
                                                    Pageable pageable);

    @Query("""
           SELECT t FROM AccountTransactionEntity t
            WHERE t.accountId = :accountId
              AND t.postedAt >= :from
            ORDER BY t.postedAt DESC
           """)
    Page<AccountTransactionEntity> findFromAccount(@Param("accountId") String accountId,
                                                   @Param("from") Instant from,
                                                   Pageable pageable);

    @Query("""
           SELECT t FROM AccountTransactionEntity t
            WHERE t.accountId = :accountId
              AND t.postedAt < :to
            ORDER BY t.postedAt DESC
           """)
    Page<AccountTransactionEntity> findToAccount(@Param("accountId") String accountId,
                                                 @Param("to") Instant to,
                                                 Pageable pageable);

    @Query("""
           SELECT t FROM AccountTransactionEntity t
            WHERE t.accountId = :accountId
              AND t.postedAt >= :from
              AND t.postedAt <  :to
            ORDER BY t.postedAt DESC
           """)
    Page<AccountTransactionEntity> findRangeAccount(@Param("accountId") String accountId,
                                                    @Param("from") Instant from,
                                                    @Param("to") Instant to,
                                                    Pageable pageable);

    /**
     * Single dispatcher used by callers; routes to the right concrete query
     * based on which of {@code from} / {@code to} were supplied so the
     * Postgres null-inference bug never has a chance to fire.
     */
    default Page<AccountTransactionEntity> findForAccount(String accountId,
                                                          Instant from,
                                                          Instant to,
                                                          Pageable pageable) {
        if (from != null && to != null) return findRangeAccount(accountId, from, to, pageable);
        if (from != null) return findFromAccount(accountId, from, pageable);
        if (to != null) return findToAccount(accountId, to, pageable);
        return findAllByAccount(accountId, pageable);
    }
}
