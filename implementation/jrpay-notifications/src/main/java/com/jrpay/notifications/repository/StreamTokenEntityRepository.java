package com.jrpay.notifications.repository;

import com.jrpay.notifications.domain.StreamTokenEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StreamTokenEntityRepository extends JpaRepository<StreamTokenEntity, String> {
}
