package com.jrpay.identity.repository;

import com.jrpay.identity.domain.StepUpToken;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StepUpTokenRepository extends JpaRepository<StepUpToken, String> {
}
