package com.jrpay.fraud.casework.domain;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FraudCaseRepository extends JpaRepository<FraudCaseEntity, UUID> {

    Optional<FraudCaseEntity> findByPaymentId(UUID paymentId);

    @Query("""
           SELECT c FROM FraudCaseEntity c
            WHERE c.status IN :statuses
            ORDER BY c.score DESC, c.createdAt ASC
           """)
    Page<FraudCaseEntity> listByStatus(@Param("statuses") Collection<FraudCaseEntity.Status> statuses,
                                       Pageable pageable);

    @Query("""
           SELECT c FROM FraudCaseEntity c
            WHERE c.status IN :statuses
              AND (:minScore IS NULL OR c.score >= :minScore)
              AND (:maxScore IS NULL OR c.score <= :maxScore)
            ORDER BY c.score DESC, c.createdAt ASC
           """)
    Page<FraudCaseEntity> listByStatusScored(@Param("statuses") Collection<FraudCaseEntity.Status> statuses,
                                             @Param("minScore") java.math.BigDecimal minScore,
                                             @Param("maxScore") java.math.BigDecimal maxScore,
                                             Pageable pageable);

    long countByStatusIn(Collection<FraudCaseEntity.Status> statuses);

    long countByAssigneeAndStatusIn(UUID assignee, Collection<FraudCaseEntity.Status> statuses);

    @Query("""
           SELECT COUNT(c) FROM FraudCaseEntity c
            WHERE c.status IN :statuses
              AND c.slaDeadline < :now
           """)
    long countSlaBreached(@Param("statuses") Collection<FraudCaseEntity.Status> statuses,
                          @Param("now") Instant now);

    List<FraudCaseEntity> findByCaseIdIn(Collection<UUID> caseIds);
}
