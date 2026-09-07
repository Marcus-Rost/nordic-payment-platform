package com.jrpay.audit.repository;

import com.jrpay.audit.domain.AuditExportJob;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditExportJobRepository extends JpaRepository<AuditExportJob, UUID> {

    Optional<AuditExportJob> findByNonce(String nonce);
}
