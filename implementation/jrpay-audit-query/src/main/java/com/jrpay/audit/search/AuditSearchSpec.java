package com.jrpay.audit.search;

import com.jrpay.audit.domain.AuditEvent;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Predicate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;

/**
 * Composes a JPA {@link Specification} from {@link AuditSearchCriteria}.
 * Predicate-by-predicate so we never pass null parameters into the query
 * (the same Postgres parameter-type-inference issue we hit in
 * {@code jrpay-notifications}).
 */
public final class AuditSearchSpec {

    private AuditSearchSpec() {
    }

    public static Specification<AuditEvent> from(AuditSearchCriteria c) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (c.from() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("occurredAt"), c.from()));
            }
            if (c.to() != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("occurredAt"), c.to()));
            }
            if (c.types() != null && !c.types().isEmpty()) {
                predicates.add(root.get("type").in(c.types()));
            }
            if (c.actor() != null && !c.actor().isBlank()) {
                predicates.add(cb.equal(root.get("actor"), c.actor()));
            }
            if (c.resourceType() != null) {
                predicates.add(cb.equal(root.get("resourceType"), c.resourceType()));
            }
            if (c.resourceId() != null && !c.resourceId().isBlank()) {
                predicates.add(cb.equal(root.get("resourceId"), c.resourceId()));
            }
            if (c.outcomes() != null && !c.outcomes().isEmpty()) {
                predicates.add(root.get("outcome").in(c.outcomes()));
            }
            if (c.severities() != null && !c.severities().isEmpty()) {
                predicates.add(root.get("severity").in(c.severities()));
            }
            if (c.customerUserId() != null) {
                predicates.add(cb.equal(root.get("customerUserId"), c.customerUserId()));
            }
            if (c.operatorUserId() != null) {
                predicates.add(cb.equal(root.get("operatorUserId"), c.operatorUserId()));
            }
            if (c.consentId() != null) {
                predicates.add(cb.equal(root.get("consentId"), c.consentId()));
            }
            if (c.q() != null && !c.q().isBlank()) {
                predicates.add(freeTextPredicate(cb, root, c.q().trim()));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    private static Predicate freeTextPredicate(CriteriaBuilder cb,
                                                jakarta.persistence.criteria.Root<AuditEvent> root,
                                                String q) {
        String like = "%" + q.toLowerCase() + "%";
        return cb.or(
                cb.like(cb.lower(cb.coalesce(root.get("summary"), "")), like),
                cb.like(cb.lower(root.get("actor")), like),
                cb.like(cb.lower(cb.coalesce(root.get("resourceId"), "")), like)
        );
    }

    public record AuditSearchCriteria(
            OffsetDateTime from,
            OffsetDateTime to,
            List<String> types,
            String actor,
            String resourceType,
            String resourceId,
            List<AuditEvent.Outcome> outcomes,
            List<AuditEvent.Severity> severities,
            UUID customerUserId,
            UUID operatorUserId,
            UUID consentId,
            String q
    ) { }
}
