package com.jrpay.fraud.casework.domain;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StreamTokenRepository extends JpaRepository<StreamTokenEntity, String> {

    Optional<StreamTokenEntity> findByTokenHash(String tokenHash);
}
