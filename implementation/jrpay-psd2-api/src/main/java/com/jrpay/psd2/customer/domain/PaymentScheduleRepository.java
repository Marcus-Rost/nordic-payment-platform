package com.jrpay.psd2.customer.domain;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentScheduleRepository extends JpaRepository<PaymentScheduleEntity, String> {

    Optional<PaymentScheduleEntity> findByScheduleIdAndCustomerUserId(String scheduleId, UUID customerUserId);

    @Query("""
           SELECT s FROM PaymentScheduleEntity s
            WHERE s.customerUserId = :uid
              AND s.status IN :statuses
            ORDER BY s.executeAt ASC
           """)
    Page<PaymentScheduleEntity> listFor(@Param("uid") UUID customerUserId,
                                        @Param("statuses") Collection<PaymentScheduleEntity.Status> statuses,
                                        Pageable pageable);
}
