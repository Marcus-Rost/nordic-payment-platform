package com.jrpay.psd2.customer.domain;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ProcessedKafkaEventRepository
        extends JpaRepository<ProcessedKafkaEvent, ProcessedKafkaEvent.Pk> {
}
