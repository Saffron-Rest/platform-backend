package com.saffron.cashflow.repository;

import com.saffron.cashflow.domain.CardSettlement;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface CardSettlementRepository extends JpaRepository<CardSettlement, String> {

    List<CardSettlement> findByEffectiveDateBetweenOrderByEffectiveDateDescCreatedAtDesc(
            LocalDate from, LocalDate to);

    Optional<CardSettlement> findByLinkedKindAndLinkedRefId(String linkedKind, String linkedRefId);
}
