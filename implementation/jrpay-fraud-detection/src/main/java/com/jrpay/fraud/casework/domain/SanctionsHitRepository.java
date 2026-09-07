package com.jrpay.fraud.casework.domain;

import java.time.Instant;
import java.util.Collection;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SanctionsHitRepository extends JpaRepository<SanctionsHitEntity, UUID> {

    @Query("""
           SELECT h FROM SanctionsHitEntity h
            WHERE h.status IN :statuses
            ORDER BY h.matchScore DESC, h.createdAt ASC
           """)
    Page<SanctionsHitEntity> listByStatus(
            @Param("statuses") Collection<SanctionsHitEntity.Status> statuses,
            Pageable pageable);

    @Query("""
           SELECT h FROM SanctionsHitEntity h
            WHERE h.status IN :statuses
              AND h.matchedListId = :listId
            ORDER BY h.matchScore DESC, h.createdAt ASC
           """)
    Page<SanctionsHitEntity> listByStatusAndList(
            @Param("statuses") Collection<SanctionsHitEntity.Status> statuses,
            @Param("listId") String listId,
            Pageable pageable);

    long countByStatusIn(Collection<SanctionsHitEntity.Status> statuses);

    @Query("""
           SELECT COUNT(h) FROM SanctionsHitEntity h
            WHERE h.status IN :statuses
              AND h.slaDeadline < :now
           """)
    long countSlaBreached(@Param("statuses") Collection<SanctionsHitEntity.Status> statuses,
                          @Param("now") Instant now);
}
