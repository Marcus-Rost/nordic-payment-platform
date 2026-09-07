package com.jrpay.ledger.projection;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AdminIdempotencyRepository
        extends JpaRepository<AdminIdempotencyEntity, AdminIdempotencyEntity.Pk> {

    default AdminIdempotencyEntity.Pk pkOf(UUID operatorId, String key) {
        return new AdminIdempotencyEntity.Pk(operatorId, key);
    }
}
