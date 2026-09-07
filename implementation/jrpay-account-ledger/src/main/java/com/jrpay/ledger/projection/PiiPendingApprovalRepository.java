package com.jrpay.ledger.projection;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PiiPendingApprovalRepository
        extends JpaRepository<PiiPendingApprovalEntity, String> {

    Optional<PiiPendingApprovalEntity> findByApprovalRef(String approvalRef);
}
