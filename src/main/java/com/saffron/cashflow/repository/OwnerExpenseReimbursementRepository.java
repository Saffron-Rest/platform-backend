package com.saffron.cashflow.repository;

import com.saffron.cashflow.domain.OwnerExpenseReimbursement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface OwnerExpenseReimbursementRepository
        extends JpaRepository<OwnerExpenseReimbursement, String> {

    @Query("SELECT r FROM OwnerExpenseReimbursement r WHERE r.ownerExpense.id = :expenseId "
            + "ORDER BY r.paidDate DESC, r.createdAt DESC")
    List<OwnerExpenseReimbursement> findByExpenseId(@Param("expenseId") String expenseId);

    @Query("SELECT r FROM OwnerExpenseReimbursement r WHERE r.paidDate BETWEEN :from AND :to "
            + "ORDER BY r.paidDate DESC, r.createdAt DESC")
    List<OwnerExpenseReimbursement> findByPaidDateBetween(
            @Param("from") LocalDate from, @Param("to") LocalDate to);
}
