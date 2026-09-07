package com.jrpay.fraud.casework.domain;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FraudTimelineRepository extends JpaRepository<FraudTimelineEntry, Long> {

    List<FraudTimelineEntry> findByCaseIdOrderByOccurredAtAsc(UUID caseId);
}
