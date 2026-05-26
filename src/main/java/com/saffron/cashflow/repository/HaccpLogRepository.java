package com.saffron.cashflow.repository;

import com.saffron.cashflow.domain.HaccpKind;
import com.saffron.cashflow.domain.HaccpLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface HaccpLogRepository extends JpaRepository<HaccpLog, String> {

    @Query("SELECT h FROM HaccpLog h WHERE h.recordedOn BETWEEN :from AND :to "
            + "AND (:kind IS NULL OR h.kind = :kind) "
            + "ORDER BY h.recordedOn DESC, h.recordedAt DESC")
    List<HaccpLog> findBetween(@Param("from") LocalDate from,
                                @Param("to") LocalDate to,
                                @Param("kind") HaccpKind kind);

    @Query("SELECT h FROM HaccpLog h WHERE h.recordedOn = :date "
            + "ORDER BY h.recordedAt DESC")
    List<HaccpLog> findForDate(@Param("date") LocalDate date);
}
