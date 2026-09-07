package com.jrpay.ledger.projection;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PiiRevealRepository extends JpaRepository<PiiRevealEntity, UUID> {

    @Query("""
           SELECT COUNT(p) FROM PiiRevealEntity p
            WHERE p.operatorId = :operatorId
              AND p.accountId = :accountId
              AND p.revealedAt >= :since
           """)
    long countRecent(@Param("operatorId") UUID operatorId,
                     @Param("accountId") String accountId,
                     @Param("since") Instant since);

    Optional<PiiRevealEntity> findFirstByAccountIdAndOperatorIdOrderByRevealedAtDesc(
            String accountId, UUID operatorId);
}
