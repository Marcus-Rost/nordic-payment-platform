package com.jrpay.fraud.casework.domain;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SanctionsListRepository extends JpaRepository<SanctionsListEntity, String> {
}
