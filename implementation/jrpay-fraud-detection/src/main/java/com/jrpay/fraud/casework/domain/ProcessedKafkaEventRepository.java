package com.jrpay.fraud.casework.domain;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ProcessedKafkaEventRepository
        extends JpaRepository<ProcessedKafkaEvent, ProcessedKafkaEvent.Pk> {
}
