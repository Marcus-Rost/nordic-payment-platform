package com.jrpay.billpay.domain;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BeneficiaryRepository extends JpaRepository<Beneficiary, String> {

    Optional<Beneficiary> findByBeneficiaryIdAndCustomerUserId(String beneficiaryId, UUID customerUserId);

    Optional<Beneficiary> findByCustomerUserIdAndIban(UUID customerUserId, String iban);

    @Query("""
           SELECT b FROM Beneficiary b
            WHERE b.customerUserId = :uid
            ORDER BY b.lastPaidAt DESC NULLS LAST, b.createdAt DESC
           """)
    Page<Beneficiary> search(@Param("uid") UUID customerUserId, Pageable pageable);

    @Query("""
           SELECT b FROM Beneficiary b
            WHERE b.customerUserId = :uid
              AND (LOWER(b.name) LIKE LOWER(CONCAT('%', :q, '%'))
                   OR LOWER(COALESCE(b.notes, '')) LIKE LOWER(CONCAT('%', :q, '%'))
                   OR b.iban LIKE CONCAT('%', :q))
            ORDER BY b.lastPaidAt DESC NULLS LAST, b.createdAt DESC
           """)
    Page<Beneficiary> searchWithQuery(@Param("uid") UUID customerUserId,
                                       @Param("q") String q,
                                       Pageable pageable);
}
