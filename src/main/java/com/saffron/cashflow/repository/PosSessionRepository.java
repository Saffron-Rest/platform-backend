package com.saffron.cashflow.repository;

import com.saffron.cashflow.domain.PosSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface PosSessionRepository extends JpaRepository<PosSession, String> {

    Optional<PosSession> findFirstByCashierIdAndStatusOrderByOpenedAtDesc(
            String cashierId, PosSession.Status status);

    List<PosSession> findByBusinessDayOrderByOpenedAtAsc(LocalDate businessDay);
}
