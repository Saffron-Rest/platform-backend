package com.saffron.cashflow.service;

import com.saffron.cashflow.domain.AuditAction;
import com.saffron.cashflow.domain.AuditLog;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

public final class AuditSpecification {

    private AuditSpecification() {}

    public static Specification<AuditLog> filter(
            AuditAction action,
            String entityType,
            String userId,
            String entityId,
            LocalDate from,
            LocalDate to,
            String search) {
        return (root, query, cb) -> {
            List<Predicate> preds = new ArrayList<>();
            if (action != null) preds.add(cb.equal(root.get("action"), action));
            if (entityType != null && !entityType.isBlank()) {
                preds.add(cb.equal(root.get("entityType"), entityType));
            }
            if (userId != null && !userId.isBlank()) {
                preds.add(cb.equal(root.get("user").get("id"), userId));
            }
            if (entityId != null && !entityId.isBlank()) {
                preds.add(cb.equal(root.get("entityId"), entityId));
            }
            if (from != null) {
                preds.add(cb.greaterThanOrEqualTo(root.get("createdAt"), from.atStartOfDay().toInstant(ZoneOffset.UTC)));
            }
            if (to != null) {
                preds.add(cb.lessThan(root.get("createdAt"), to.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC)));
            }
            if (search != null && !search.isBlank()) {
                String pattern = "%" + search.toLowerCase() + "%";
                preds.add(cb.or(
                        cb.like(cb.lower(root.get("summary")), pattern),
                        cb.like(cb.lower(root.get("entityType")), pattern),
                        cb.like(cb.lower(root.get("entityId")), pattern),
                        cb.like(cb.lower(root.get("user").get("name")), pattern),
                        cb.like(cb.lower(root.get("user").get("email")), pattern)));
            }
            return cb.and(preds.toArray(new Predicate[0]));
        };
    }
}
