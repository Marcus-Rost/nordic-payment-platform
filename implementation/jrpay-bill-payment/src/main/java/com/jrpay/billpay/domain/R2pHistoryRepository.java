package com.jrpay.billpay.domain;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface R2pHistoryRepository extends JpaRepository<R2pHistoryEntry, Long> {

    List<R2pHistoryEntry> findByRequestIdOrderByOccurredAtAsc(String requestId);
}
