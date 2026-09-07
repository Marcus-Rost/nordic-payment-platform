package com.jrpay.billpay.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MerchantRepository extends JpaRepository<Merchant, String> {

    Optional<Merchant> findByAvtaleId(String avtaleId);

    @Query(value = """
           SELECT *
             FROM merchants
            WHERE display_name % :q
               OR LOWER(display_name) LIKE LOWER(CONCAT('%', :q, '%'))
               OR avtale_id LIKE CONCAT(:q, '%')
            ORDER BY similarity(display_name, :q) DESC, display_name ASC
           """, nativeQuery = true)
    List<Merchant> search(@Param("q") String q, Pageable pageable);
}
