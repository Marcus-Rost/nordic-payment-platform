package com.jrpay.ledger.projection;

import com.jrpay.ledger.domain.ReservationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ReservationRepository extends JpaRepository<ReservationEntity, String> {

    Optional<ReservationEntity> findByReservationId(String reservationId);

    List<ReservationEntity> findByAccountIdAndStatus(String accountId, ReservationStatus status);

    /** Find expired reservations for the auto-release scanner. */
    List<ReservationEntity> findByStatusAndCreatedAtBefore(ReservationStatus status, Instant cutoff);
}
