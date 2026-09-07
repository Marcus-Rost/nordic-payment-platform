package com.jrpay.audit.repository;

import com.jrpay.audit.domain.AuditEvent;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AuditEventRepository
        extends JpaRepository<AuditEvent, String>, JpaSpecificationExecutor<AuditEvent> {

    @Query("select max(e.chainOffset) from AuditEvent e")
    Optional<Long> findMaxChainOffset();

    @Query("select e from AuditEvent e where e.chainOffset = :offset")
    Optional<AuditEvent> findByChainOffset(@Param("offset") long offset);

    @Query("""
            select e from AuditEvent e
            where e.chainOffset between :fromOffset and :toOffset
            order by e.chainOffset asc
            """)
    List<AuditEvent> findRange(@Param("fromOffset") long fromOffset,
                               @Param("toOffset") long toOffset);
}
