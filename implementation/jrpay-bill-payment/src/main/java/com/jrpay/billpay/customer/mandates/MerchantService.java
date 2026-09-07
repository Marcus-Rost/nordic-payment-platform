package com.jrpay.billpay.customer.mandates;

import com.jrpay.billpay.customer.CustomerProblemException;
import com.jrpay.billpay.customer.mandates.dto.MerchantDto;
import com.jrpay.billpay.customer.mandates.dto.MerchantPage;
import com.jrpay.billpay.domain.MandateFrequency;
import com.jrpay.billpay.domain.Merchant;
import com.jrpay.billpay.domain.MerchantRepository;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MerchantService {

    private final MerchantRepository merchants;

    public MerchantService(MerchantRepository merchants) {
        this.merchants = merchants;
    }

    @Transactional(readOnly = true)
    public MerchantPage search(String query, int size) {
        if (query == null || query.length() < 2) {
            throw CustomerProblemException.invalidInput("q must be at least 2 characters");
        }
        List<Merchant> rows = merchants.search(query, PageRequest.of(0, Math.min(size, 50)));
        return new MerchantPage(rows.stream().map(MerchantService::toDto).toList());
    }

    @Transactional(readOnly = true)
    public MerchantDto get(String merchantId) {
        return merchants.findById(merchantId)
                .map(MerchantService::toDto)
                .orElseThrow(() -> CustomerProblemException.merchantNotInRegistry(merchantId));
    }

    public static MerchantDto toDto(Merchant m) {
        return new MerchantDto(
                m.getMerchantId(),
                m.getAvtaleId(),
                m.getDisplayName(),
                m.getTradingName(),
                m.getIban(),
                m.getBic(),
                m.getLogoUrl(),
                m.getAcceptedFrequencies().stream().map(MandateFrequency::name).toList(),
                m.getRegistryUpdatedAt());
    }
}
