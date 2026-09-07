package com.jrpay.fraud.casework.domain;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SanctionsListRefreshRunRepository
        extends JpaRepository<SanctionsListRefreshRunEntity, UUID> {
}
