package com.jrpay.identity.repository;

import com.jrpay.identity.domain.PasswordResetRequest;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PasswordResetRequestRepository extends JpaRepository<PasswordResetRequest, UUID> {

    long countByIdentifierHashAndIssuedAtAfter(String identifierHash, OffsetDateTime issuedAt);
}
