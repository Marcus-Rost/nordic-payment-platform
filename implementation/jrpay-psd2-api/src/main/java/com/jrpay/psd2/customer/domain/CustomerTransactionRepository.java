package com.jrpay.psd2.customer.domain;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CustomerTransactionRepository
        extends JpaRepository<CustomerTransactionEntity, CustomerTransactionEntity.Pk> {

    @Query("""
           SELECT t FROM CustomerTransactionEntity t
            WHERE t.accountId = :accountId
              AND t.customerUserId = :uid
            ORDER BY t.postedAt DESC
           """)
    Page<CustomerTransactionEntity> listForAccount(@Param("accountId") String accountId,
                                                   @Param("uid") UUID customerUserId,
                                                   Pageable pageable);

    @Query("""
           SELECT t FROM CustomerTransactionEntity t
            WHERE t.accountId = :accountId
              AND t.customerUserId = :uid
              AND t.postedAt >= :from
            ORDER BY t.postedAt DESC
           """)
    Page<CustomerTransactionEntity> listForAccountFrom(@Param("accountId") String accountId,
                                                       @Param("uid") UUID customerUserId,
                                                       @Param("from") Instant from,
                                                       Pageable pageable);

    @Query("""
           SELECT t FROM CustomerTransactionEntity t
            WHERE t.accountId = :accountId
              AND t.customerUserId = :uid
              AND t.postedAt <  :to
            ORDER BY t.postedAt DESC
           """)
    Page<CustomerTransactionEntity> listForAccountTo(@Param("accountId") String accountId,
                                                     @Param("uid") UUID customerUserId,
                                                     @Param("to") Instant to,
                                                     Pageable pageable);

    @Query("""
           SELECT t FROM CustomerTransactionEntity t
            WHERE t.accountId = :accountId
              AND t.customerUserId = :uid
              AND t.postedAt >= :from
              AND t.postedAt <  :to
            ORDER BY t.postedAt DESC
           """)
    Page<CustomerTransactionEntity> listForAccountRange(@Param("accountId") String accountId,
                                                        @Param("uid") UUID customerUserId,
                                                        @Param("from") Instant from,
                                                        @Param("to") Instant to,
                                                        Pageable pageable);

    @Query("""
           SELECT t FROM CustomerTransactionEntity t
            WHERE t.transactionId = :txId
              AND t.customerUserId = :uid
           """)
    java.util.List<CustomerTransactionEntity> findOwnedById(@Param("txId") String txId,
                                                            @Param("uid") UUID customerUserId);
}
