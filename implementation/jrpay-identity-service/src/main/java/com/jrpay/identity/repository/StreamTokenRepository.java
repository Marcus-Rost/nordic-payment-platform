package com.jrpay.identity.repository;

import com.jrpay.identity.domain.StreamToken;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StreamTokenRepository extends JpaRepository<StreamToken, String> {
}
