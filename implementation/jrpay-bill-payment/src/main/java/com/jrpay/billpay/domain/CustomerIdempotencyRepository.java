package com.jrpay.billpay.domain;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CustomerIdempotencyRepository
        extends JpaRepository<CustomerIdempotencyEntity, CustomerIdempotencyEntity.Pk> {
}
