package com.jrpay.psd2.customer.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CustomerPaymentRepository extends JpaRepository<CustomerPaymentEntity, String> {

    Optional<CustomerPaymentEntity> findByPaymentIdAndCustomerUserId(
            String paymentId, UUID customerUserId);

    Optional<CustomerPaymentEntity> findByIdempotencyKey(String idempotencyKey);

    Page<CustomerPaymentEntity> findByCustomerUserIdOrderByCreatedAtDesc(
            UUID customerUserId, Pageable pageable);

    /**
     * Probable-duplicate detection: same customer + creditor + amount + currency
     * within a short window. The service decides what window is "short" by
     * passing {@code since}.
     */
    @Query("""
           SELECT p FROM CustomerPaymentEntity p
            WHERE p.customerUserId = :uid
              AND p.creditorIban = :iban
              AND p.amount = :amount
              AND p.currency = :currency
              AND p.createdAt >= :since
              AND p.status NOT IN ('CANCELLED','REJECTED','RETURNED')
            ORDER BY p.createdAt DESC
           """)
    List<CustomerPaymentEntity> findProbableDuplicates(@Param("uid") UUID customerUserId,
                                                       @Param("iban") String creditorIban,
                                                       @Param("amount") BigDecimal amount,
                                                       @Param("currency") String currency,
                                                       @Param("since") Instant since);
}
