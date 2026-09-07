package com.jrpay.psd2.customer.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CustomerAccountRepository extends JpaRepository<CustomerAccountEntity, String> {

    List<CustomerAccountEntity> findByCustomerUserIdOrderByPrimaryDescOpenedAtAsc(UUID customerUserId);

    Optional<CustomerAccountEntity> findByAccountIdAndCustomerUserId(String accountId, UUID customerUserId);
}
