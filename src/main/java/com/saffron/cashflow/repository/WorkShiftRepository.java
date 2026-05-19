package com.saffron.cashflow.repository;

import com.saffron.cashflow.domain.WorkShift;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface WorkShiftRepository extends JpaRepository<WorkShift, String> {

    Optional<WorkShift> findByUser_IdAndDate(String userId, LocalDate date);

    @Query("SELECT w FROM WorkShift w JOIN FETCH w.user WHERE w.user.id = :userId AND w.date = :date")
    Optional<WorkShift> findByUser_IdAndDateWithUser(String userId, LocalDate date);

    @Query("SELECT w FROM WorkShift w JOIN FETCH w.user WHERE w.id = :id")
    Optional<WorkShift> findByIdWithUser(String id);

    @Query("SELECT w FROM WorkShift w JOIN FETCH w.user WHERE w.date = :date ORDER BY w.user.name")
    List<WorkShift> findByDateWithUser(LocalDate date);

    @Query("SELECT w FROM WorkShift w JOIN FETCH w.user WHERE w.date BETWEEN :from AND :to AND w.working = true ORDER BY w.date, w.user.name")
    List<WorkShift> findWorkingBetween(LocalDate from, LocalDate to);
}
