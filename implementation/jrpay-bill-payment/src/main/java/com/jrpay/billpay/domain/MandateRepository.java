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

public interface MandateRepository extends JpaRepository<Mandate, String> {

    List<Mandate> findByDebtorIbanAndStatus(String debtorIban, Mandate.MandateStatus status);

    Optional<Mandate> findByMandateIdAndCustomerUserId(String mandateId, UUID customerUserId);

    List<Mandate> findByCustomerUserIdAndMerchantIdAndStatus(
            UUID customerUserId, String merchantId, Mandate.MandateStatus status);

    @Query("""
           SELECT m FROM Mandate m
            WHERE m.customerUserId = :uid
              AND m.status IN :statuses
            ORDER BY m.createdAt DESC
           """)
    Page<Mandate> searchInbox(@Param("uid") UUID customerUserId,
                              @Param("statuses") Collection<Mandate.MandateStatus> statuses,
                              Pageable pageable);

    @Query("""
           SELECT m FROM Mandate m
            WHERE m.customerUserId = :uid
              AND m.status IN :statuses
              AND LOWER(m.merchantId) LIKE LOWER(CONCAT('%', :q, '%'))
            ORDER BY m.createdAt DESC
           """)
    Page<Mandate> searchInboxWithQuery(@Param("uid") UUID customerUserId,
                                       @Param("statuses") Collection<Mandate.MandateStatus> statuses,
                                       @Param("q") String q,
                                       Pageable pageable);
}
