package com.jrpay.psd2.consent;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface ConsentRepository extends JpaRepository<Consent, String> {

    List<Consent> findByAccountHolderIdAndStatus(String accountHolderId, Consent.ConsentStatus status);

    List<Consent> findByTppIdAndAccountHolderIdAndStatus(String tppId, String accountHolderId,
                                                         Consent.ConsentStatus status);
}
