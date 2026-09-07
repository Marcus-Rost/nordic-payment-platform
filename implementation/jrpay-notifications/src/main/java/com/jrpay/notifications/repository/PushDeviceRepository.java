package com.jrpay.notifications.repository;

import com.jrpay.notifications.domain.PushDevice;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PushDeviceRepository extends JpaRepository<PushDevice, UUID> {

    List<PushDevice> findByUserIdAndRevokedAtIsNullOrderByLastSeenAtDesc(UUID userId);

    Optional<PushDevice> findByUserIdAndTokenHash(UUID userId, String tokenHash);
}
