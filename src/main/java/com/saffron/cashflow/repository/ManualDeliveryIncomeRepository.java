package com.saffron.cashflow.repository;

import com.saffron.cashflow.domain.ManualDeliveryIncome;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDate;
import java.util.List;

public interface ManualDeliveryIncomeRepository extends JpaRepository<ManualDeliveryIncome, String> {

    List<ManualDeliveryIncome> findByEffectiveDateBetweenOrderByEffectiveDateDescCreatedAtDesc(
            LocalDate from, LocalDate to);
}
