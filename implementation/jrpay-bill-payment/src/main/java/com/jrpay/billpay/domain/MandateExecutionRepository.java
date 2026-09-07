package com.jrpay.billpay.domain;

import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MandateExecutionRepository extends JpaRepository<MandateExecution, UUID> {

    Page<MandateExecution> findByMandateIdOrderByAttemptedAtDesc(String mandateId, Pageable pageable);

    long countByMandateIdAndStatus(String mandateId, String status);
}
