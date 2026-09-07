package com.jrpay.audit.repository;

import com.jrpay.audit.domain.ProcessedKafkaEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProcessedKafkaEventRepository
        extends JpaRepository<ProcessedKafkaEvent, ProcessedKafkaEvent.Pk> {
}
