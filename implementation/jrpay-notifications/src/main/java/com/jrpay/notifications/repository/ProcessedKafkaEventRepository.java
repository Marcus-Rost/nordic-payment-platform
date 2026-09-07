package com.jrpay.notifications.repository;

import com.jrpay.notifications.domain.ProcessedKafkaEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProcessedKafkaEventRepository
        extends JpaRepository<ProcessedKafkaEvent, ProcessedKafkaEvent.Pk> {
}
