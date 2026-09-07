package com.jrpay.identity.repository;

import com.jrpay.identity.domain.ScaSession;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ScaSessionRepository extends JpaRepository<ScaSession, UUID> {
}
