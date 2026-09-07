package com.jrpay.billpay.customer.beneficiaries;

import com.jrpay.billpay.audit.CustomerAuditEvent;
import com.jrpay.billpay.audit.CustomerAuditPublisher;
import com.jrpay.billpay.config.AuthenticatedUser;
import com.jrpay.billpay.customer.CustomerProblemException;
import com.jrpay.billpay.customer.IbanValidator;
import com.jrpay.billpay.customer.beneficiaries.dto.BeneficiaryAddRequest;
import com.jrpay.billpay.customer.beneficiaries.dto.BeneficiaryDetail;
import com.jrpay.billpay.customer.beneficiaries.dto.BeneficiaryDto;
import com.jrpay.billpay.customer.beneficiaries.dto.BeneficiaryPage;
import com.jrpay.billpay.customer.beneficiaries.dto.BeneficiaryPendingSca;
import com.jrpay.billpay.customer.beneficiaries.dto.BeneficiaryUpdateRequest;
import com.jrpay.billpay.domain.Beneficiary;
import com.jrpay.billpay.domain.BeneficiaryRepository;
import com.jrpay.billpay.sca.DynamicLinkingHash;
import com.jrpay.billpay.sca.ScaInitiator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BeneficiaryService {

    private final BeneficiaryRepository beneficiaries;
    private final ScaInitiator scaInitiator;
    private final DynamicLinkingHash dlHasher;
    private final CustomerAuditPublisher audit;
    private final AuthenticatedUser caller;

    public BeneficiaryService(BeneficiaryRepository beneficiaries,
                              ScaInitiator scaInitiator,
                              DynamicLinkingHash dlHasher,
                              CustomerAuditPublisher audit,
                              AuthenticatedUser caller) {
        this.beneficiaries = beneficiaries;
        this.scaInitiator = scaInitiator;
        this.dlHasher = dlHasher;
        this.audit = audit;
        this.caller = caller;
    }

    @Transactional(readOnly = true)
    public BeneficiaryPage list(String query, int page, int size) {
        UUID uid = caller.requireUserId();
        String q = blankToNull(query);
        Page<Beneficiary> rows = (q == null)
                ? beneficiaries.search(uid, PageRequest.of(page, size))
                : beneficiaries.searchWithQuery(uid, q, PageRequest.of(page, size));
        List<BeneficiaryDto> items = rows.getContent().stream().map(this::toSummaryDto).toList();
        return new BeneficiaryPage(items, new BeneficiaryPage.Page(
                rows.getNumber(), rows.getSize(),
                rows.getTotalElements(), rows.getTotalPages()));
    }

    @Transactional(readOnly = true)
    public BeneficiaryDetail detail(String beneficiaryId) {
        Beneficiary b = requireOwn(beneficiaryId);
        return toDetail(b);
    }

    @Transactional
    public BeneficiaryPendingSca add(BeneficiaryAddRequest req) {
        UUID uid = caller.requireUserId();
        String ibanCompact = IbanValidator.compact(req.iban());
        if (!IbanValidator.isValid(ibanCompact)) {
            throw CustomerProblemException.invalidIban("IBAN failed mod-97 check");
        }
        beneficiaries.findByCustomerUserIdAndIban(uid, ibanCompact)
                .ifPresent(existing -> {
                    throw CustomerProblemException.duplicateIban(
                            "An entry with this IBAN already exists",
                            existing.getBeneficiaryId());
                });

        String beneficiaryId = "be-" + UUID.randomUUID().toString().substring(0, 12);
        Map<String, Object> dlInputs = dlHasher.beneficiaryInputs(beneficiaryId, req.name(), ibanCompact);
        ScaInitiator.InitiateResult init = scaInitiator.start(dlInputs, false);

        Beneficiary entity = new Beneficiary(
                beneficiaryId, uid, req.name(), ibanCompact,
                null, null, "NOK", req.notes(),
                req.vopResult(), req.vopPayerOverride(),
                init.scaSessionId(), init.dlHash());
        beneficiaries.save(entity);
        if (init.exempt()) {
            entity.activate();
            beneficiaries.save(entity);
        }
        publishAudit("beneficiary.added", entity, Map.of(
                "scaSessionId", init.scaSessionId().toString(),
                "exempt", init.exempt()));
        return new BeneficiaryPendingSca(
                beneficiaryId, init.scaSessionId(), init.dlHash(),
                init.exempt(), init.stepUpToken(),
                init.factorType() == null ? Map.of() : Map.of("type", init.factorType()));
    }

    @Transactional
    public BeneficiaryDetail update(String beneficiaryId, BeneficiaryUpdateRequest req) {
        Beneficiary b = requireOwn(beneficiaryId);
        if (req.notes() != null) {
            b.setNotes(req.notes());
            beneficiaries.save(b);
        }
        publishAudit("beneficiary.updated", b, Map.of("notesChanged", req.notes() != null));
        return toDetail(b);
    }

    @Transactional
    public void remove(String beneficiaryId) {
        Beneficiary b = requireOwn(beneficiaryId);
        beneficiaries.delete(b);
        publishAudit("beneficiary.removed", b, Map.of());
    }

    // -----------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------

    private Beneficiary requireOwn(String beneficiaryId) {
        return beneficiaries.findByBeneficiaryIdAndCustomerUserId(beneficiaryId, caller.requireUserId())
                .orElseThrow(() -> CustomerProblemException.notFound("Beneficiary " + beneficiaryId));
    }

    private void publishAudit(String type, Beneficiary b, Map<String, Object> extra) {
        Map<String, Object> payload = new LinkedHashMap<>(extra);
        payload.put("beneficiaryId", b.getBeneficiaryId());
        audit.publish(CustomerAuditEvent.builder(type)
                .actor(caller.currentSubject())
                .customerUserId(caller.currentUserId().orElse(null))
                .resourceType("beneficiary")
                .resourceId(b.getBeneficiaryId())
                .summary(type + " for beneficiary " + b.getBeneficiaryId())
                .payload(payload)
                .build());
    }

    private BeneficiaryDto toSummaryDto(Beneficiary b) {
        return new BeneficiaryDto(
                b.getBeneficiaryId(), b.getName(),
                maskIban(b.getIban()), b.getBankName(),
                b.getLastPaidAt(),
                b.getTotalYtd().toPlainString(), b.getCurrency(),
                b.getNotes(), b.getCreatedAt(), b.getStatus().name());
    }

    private BeneficiaryDetail toDetail(Beneficiary b) {
        return new BeneficiaryDetail(
                toSummaryDto(b),
                b.getIban(), b.getBic(),
                b.getActivatedAt(),
                b.getPaymentsCountYtd(),
                b.getVopResultAtCreation(),
                b.getVopPayerOverrideAtCreation());
    }

    private static String maskIban(String iban) {
        if (iban == null) return null;
        String compact = iban.replace(" ", "");
        if (compact.length() < 8) return compact;
        String first4 = compact.substring(0, 4);
        String last4 = compact.substring(compact.length() - 4);
        int middle = compact.length() - 8;
        StringBuilder mask = new StringBuilder(first4 + " ");
        int filled = 0;
        while (filled < middle) {
            int chunk = Math.min(4, middle - filled);
            for (int i = 0; i < chunk; i++) mask.append('•');
            mask.append(' ');
            filled += chunk;
        }
        mask.append(last4);
        return mask.toString().trim();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
