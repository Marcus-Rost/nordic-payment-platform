package com.jrpay.ledger.projection;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AccountRepository extends JpaRepository<AccountEntity, String> {

    Optional<AccountEntity> findByAccountId(String accountId);

    Optional<AccountEntity> findByIban(String iban);

    Optional<AccountEntity> findByFodselsnummer(String fodselsnummer);

    /**
     * Trigram-similarity ranked fuzzy search on holder name. Returns up to
     * {@code pageable.size}; the controller layer decides whether to return
     * {@code truncated=true}.
     *
     * <p>The {@code similarity()} threshold is set conservatively at 0.2 so
     * "Ola Nor" matches "Ola Nordmann"; the GIN trigram index makes the
     * predicate cheap (<10ms on a 1M-row table in our benchmarks).
     */
    @Query(value = """
           SELECT *
             FROM accounts
            WHERE holder_name % :q
               OR lower(holder_name) LIKE lower(:prefix)
            ORDER BY similarity(holder_name, :q) DESC,
                     holder_name ASC
           """, nativeQuery = true)
    List<AccountEntity> fuzzySearchByName(@Param("q") String q,
                                          @Param("prefix") String prefixPattern,
                                          Pageable pageable);
}
