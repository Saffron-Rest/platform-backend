package com.saffron.cashflow.repository;

import com.saffron.cashflow.domain.PayRateChange;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PayRateChangeRepository extends JpaRepository<PayRateChange, String> {

    List<PayRateChange> findByUserIdOrderByEffectiveFromDescCreatedAtDesc(String userId);

    long countByUserId(String userId);

    Optional<PayRateChange> findTopByUserIdAndEffectiveFromLessThanEqualOrderByEffectiveFromDescCreatedAtDesc(
            String userId, LocalDate date);
}
