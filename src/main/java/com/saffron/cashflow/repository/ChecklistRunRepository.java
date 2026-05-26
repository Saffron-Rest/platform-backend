package com.saffron.cashflow.repository;

import com.saffron.cashflow.domain.ChecklistRun;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface ChecklistRunRepository extends JpaRepository<ChecklistRun, String> {

    /** Find today's run for a given template — there is at most one per
     *  (template, date) pair by convention. We don't enforce a unique
     *  constraint to allow correction runs but the service prefers reusing
     *  the existing row. */
    Optional<ChecklistRun> findFirstByTemplateIdAndRunDate(String templateId, LocalDate date);

    @Query("SELECT r FROM ChecklistRun r WHERE r.runDate = :date ORDER BY r.createdAt DESC")
    List<ChecklistRun> findForDate(@Param("date") LocalDate date);

    @Query("SELECT r FROM ChecklistRun r WHERE r.runDate BETWEEN :from AND :to "
            + "ORDER BY r.runDate DESC, r.createdAt DESC")
    List<ChecklistRun> findBetween(@Param("from") LocalDate from, @Param("to") LocalDate to);
}
