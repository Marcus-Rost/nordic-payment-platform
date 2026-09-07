package com.jrpay.psd2.customer.domain;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StatementJobRepository extends JpaRepository<StatementJobEntity, UUID> {

    Optional<StatementJobEntity> findByRequestIdAndCustomerUserId(UUID requestId, UUID customerUserId);
}
