package com.jrpay.identity.repository;

import com.jrpay.identity.domain.IdentitySession;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IdentitySessionRepository extends JpaRepository<IdentitySession, UUID> {

    List<IdentitySession> findByUserIdAndRevokedAtIsNullOrderByLastSeenAtDesc(UUID userId);
}
