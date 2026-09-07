package com.jrpay.audit.repository;

import com.jrpay.audit.domain.AuditExportArtefact;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditExportArtefactRepository extends JpaRepository<AuditExportArtefact, UUID> {
}
