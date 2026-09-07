package com.jrpay.fraud.casework.domain;

import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FpCacheRepository extends JpaRepository<FpCacheEntity, UUID> {

    @Query("""
           SELECT f FROM FpCacheEntity f
            WHERE f.removedAt IS NULL
            ORDER BY f.createdAt DESC
           """)
    Page<FpCacheEntity> listActive(Pageable pageable);

    @Query("""
           SELECT f FROM FpCacheEntity f
            WHERE f.removedAt IS NULL
              AND LOWER(f.matchedEntityName) LIKE LOWER(CONCAT('%', :q, '%'))
            ORDER BY f.createdAt DESC
           """)
    Page<FpCacheEntity> searchActive(@Param("q") String q, Pageable pageable);
}
