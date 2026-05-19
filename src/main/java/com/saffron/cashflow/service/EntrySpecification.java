package com.saffron.cashflow.service;

import com.saffron.cashflow.domain.DailyEntry;
import com.saffron.cashflow.domain.EntryStatus;
import org.springframework.data.jpa.domain.Specification;
import jakarta.persistence.criteria.Predicate;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

public final class EntrySpecification {

    private EntrySpecification() {}

    public static Specification<DailyEntry> filter(String cashierId, LocalDate from, LocalDate to, EntryStatus status) {
        return (root, query, cb) -> {
            List<Predicate> preds = new ArrayList<>();
            preds.add(cb.isNull(root.get("deletedAt")));
            if (cashierId != null) {
                preds.add(cb.equal(root.get("cashier").get("id"), cashierId));
            }
            if (from != null) preds.add(cb.greaterThanOrEqualTo(root.get("date"), from));
            if (to != null) preds.add(cb.lessThanOrEqualTo(root.get("date"), to));
            if (status != null) preds.add(cb.equal(root.get("status"), status));
            return cb.and(preds.toArray(new Predicate[0]));
        };
    }
}
