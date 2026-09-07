package com.jrpay.fraud.casework.domain;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BulkDecideRunRepository
        extends JpaRepository<BulkDecideRunEntity, BulkDecideRunEntity.Pk> {

    @Query("""
           SELECT MAX(b.requestedAt) FROM BulkDecideRunEntity b
            WHERE b.operatorUserId = :uid
           """)
    Instant findLastRequestedAt(@Param("uid") UUID operatorUserId);
}
