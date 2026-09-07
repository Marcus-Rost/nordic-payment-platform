package com.jrpay.notifications.repository;

import com.jrpay.notifications.domain.NotificationPreferencesEntity;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationPreferencesRepository
        extends JpaRepository<NotificationPreferencesEntity, UUID> {
}
