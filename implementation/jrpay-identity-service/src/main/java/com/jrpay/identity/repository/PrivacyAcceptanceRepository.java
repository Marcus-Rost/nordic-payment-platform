package com.jrpay.identity.repository;

import com.jrpay.identity.domain.PrivacyAcceptance;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PrivacyAcceptanceRepository extends JpaRepository<PrivacyAcceptance, PrivacyAcceptance.Pk> {

    @Query("select p from PrivacyAcceptance p where p.pk.userId = :userId order by p.acceptedAt desc")
    List<PrivacyAcceptance> findAllByUser(@Param("userId") UUID userId);

    default Optional<PrivacyAcceptance> findLatestByUser(UUID userId) {
        List<PrivacyAcceptance> all = findAllByUser(userId);
        return all.isEmpty() ? Optional.empty() : Optional.of(all.get(0));
    }
}
