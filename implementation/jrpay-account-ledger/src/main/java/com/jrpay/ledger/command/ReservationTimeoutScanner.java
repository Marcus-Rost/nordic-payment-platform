package com.jrpay.ledger.command;

import com.jrpay.ledger.domain.ReservationStatus;
import com.jrpay.ledger.projection.ReservationEntity;
import com.jrpay.ledger.projection.ReservationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Periodic scanner that auto-releases expired reservations.
 * Runs every 5 seconds, releases reservations older than 30 seconds.
 * This is the safety net — ensures no reservation is permanently stuck.
 */
@Component
public class ReservationTimeoutScanner {

    private static final Logger log = LoggerFactory.getLogger(ReservationTimeoutScanner.class);

    private final ReservationRepository reservationRepository;
    private final AccountCommandHandler commandHandler;
    private final long timeoutSeconds;

    public ReservationTimeoutScanner(
            ReservationRepository reservationRepository,
            AccountCommandHandler commandHandler,
            @Value("${jrpay.ledger.reservation.timeout-seconds:30}") long timeoutSeconds) {
        this.reservationRepository = reservationRepository;
        this.commandHandler = commandHandler;
        this.timeoutSeconds = timeoutSeconds;
    }

    @Scheduled(fixedDelayString = "${jrpay.ledger.reservation.scan-interval-ms:5000}")
    public void scanExpiredReservations() {
        Instant cutoff = Instant.now().minusSeconds(timeoutSeconds);

        List<ReservationEntity> expired = reservationRepository
                .findByStatusAndCreatedAtBefore(ReservationStatus.RESERVED, cutoff);

        for (ReservationEntity reservation : expired) {
            try {
                log.warn("Auto-releasing expired reservation {} for account {} (age: {}s)",
                        reservation.getReservationId(),
                        reservation.getAccountId(),
                        Instant.now().getEpochSecond() - reservation.getCreatedAt().getEpochSecond());

                commandHandler.release(reservation.getReservationId(), "TIMEOUT_AUTO_RELEASE");
            } catch (Exception e) {
                log.error("Failed to auto-release reservation {}: {}",
                        reservation.getReservationId(), e.getMessage());
            }
        }
    }
}
