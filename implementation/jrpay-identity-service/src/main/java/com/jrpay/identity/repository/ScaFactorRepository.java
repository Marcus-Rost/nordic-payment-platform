package com.jrpay.identity.repository;

import com.jrpay.identity.domain.ScaFactor;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ScaFactorRepository extends JpaRepository<ScaFactor, UUID> {

    List<ScaFactor> findByUserIdAndActiveTrue(UUID userId);

    Optional<ScaFactor> findFirstByUserIdAndFactorTypeAndActiveTrue(UUID userId,
                                                                   ScaFactor.FactorType factorType);
}
