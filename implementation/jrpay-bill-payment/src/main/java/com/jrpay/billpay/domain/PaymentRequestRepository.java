package com.jrpay.billpay.domain;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentRequestRepository extends JpaRepository<PaymentRequest, String> {

    List<PaymentRequest> findByMerchantIdAndStatus(String merchantId, RequestStatus status);

    List<PaymentRequest> findByDebtorIbanAndStatus(String debtorIban, RequestStatus status);

    Optional<PaymentRequest> findByIdempotencyKey(String idempotencyKey);

    Optional<PaymentRequest> findByRequestIdAndCustomerUserId(String requestId, UUID customerUserId);

    @Query("""
           SELECT p FROM PaymentRequest p
            WHERE p.customerUserId = :uid
              AND p.status IN :statuses
            ORDER BY CASE WHEN p.status = com.jrpay.billpay.domain.RequestStatus.PENDING THEN 0 ELSE 1 END,
                     p.dueDate ASC NULLS LAST,
                     p.updatedAt DESC
           """)
    Page<PaymentRequest> searchInbox(@Param("uid") UUID customerUserId,
                                     @Param("statuses") Collection<RequestStatus> statuses,
                                     Pageable pageable);

    @Query("""
           SELECT p FROM PaymentRequest p
            WHERE p.customerUserId = :uid
              AND p.status IN :statuses
              AND LOWER(p.merchantName) LIKE LOWER(CONCAT('%', :q, '%'))
            ORDER BY CASE WHEN p.status = com.jrpay.billpay.domain.RequestStatus.PENDING THEN 0 ELSE 1 END,
                     p.dueDate ASC NULLS LAST,
                     p.updatedAt DESC
           """)
    Page<PaymentRequest> searchInboxWithQuery(@Param("uid") UUID customerUserId,
                                              @Param("statuses") Collection<RequestStatus> statuses,
                                              @Param("q") String q,
                                              Pageable pageable);
}
