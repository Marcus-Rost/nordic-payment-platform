package com.jrpay.fraud.casework.domain;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FraudHeldPaymentRepository
        extends JpaRepository<FraudHeldPaymentEntity, FraudHeldPaymentEntity.Pk> {

    List<FraudHeldPaymentEntity> findByCaseId(UUID caseId);
}
