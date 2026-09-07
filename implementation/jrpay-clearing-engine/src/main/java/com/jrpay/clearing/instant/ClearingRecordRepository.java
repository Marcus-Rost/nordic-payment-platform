package com.jrpay.clearing.instant;

import org.springframework.data.jpa.repository.JpaRepository;
import java.time.Instant;
import java.util.List;

public interface ClearingRecordRepository extends JpaRepository<ClearingRecord, String> {

    List<ClearingRecord> findByStatus(PaymentState status);

    List<ClearingRecord> findByStatusAndUpdatedAtBefore(PaymentState status, Instant cutoff);
}
