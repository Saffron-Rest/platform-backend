package com.saffron.cashflow.repository;

import com.saffron.cashflow.domain.Incident;
import com.saffron.cashflow.domain.IncidentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface IncidentRepository extends JpaRepository<Incident, String> {

    /** Listing for the admin page — open incidents first, then by date desc. */
    @Query("SELECT i FROM Incident i ORDER BY "
            + "CASE WHEN i.status IN ('OPEN','IN_PROGRESS') THEN 0 ELSE 1 END, "
            + "i.occurredOn DESC, i.createdAt DESC")
    List<Incident> findAllOrdered();

    /** Used by the PDF report to aggregate incident exposure for a period. */
    @Query("SELECT i FROM Incident i WHERE i.occurredOn BETWEEN :from AND :to "
            + "ORDER BY i.occurredOn DESC")
    List<Incident> findByPeriod(@Param("from") LocalDate from, @Param("to") LocalDate to);

    long countByStatus(IncidentStatus status);
}
