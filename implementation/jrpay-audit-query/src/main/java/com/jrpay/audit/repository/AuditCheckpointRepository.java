package com.jrpay.audit.repository;

import com.jrpay.audit.domain.AuditCheckpoint;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditCheckpointRepository extends JpaRepository<AuditCheckpoint, UUID> {

    List<AuditCheckpoint> findAllByOrderByFromOffsetAsc();
}
