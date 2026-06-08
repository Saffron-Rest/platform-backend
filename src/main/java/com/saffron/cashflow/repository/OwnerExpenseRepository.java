package com.saffron.cashflow.repository;

import com.saffron.cashflow.domain.ExpenseCategory;
import com.saffron.cashflow.domain.OwnerExpense;
import com.saffron.cashflow.domain.OwnerExpenseStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public interface OwnerExpenseRepository extends JpaRepository<OwnerExpense, String> {

    @Query("SELECT e FROM OwnerExpense e ORDER BY e.expenseDate DESC, e.createdAt DESC")
    List<OwnerExpense> findAllOrdered();

    @Query("SELECT e FROM OwnerExpense e WHERE e.status IN :statuses "
            + "ORDER BY e.expenseDate ASC, e.createdAt ASC")
    List<OwnerExpense> findByStatuses(@Param("statuses") List<OwnerExpenseStatus> statuses);

    @Query("SELECT e FROM OwnerExpense e WHERE e.ownerUserId = :ownerId "
            + "ORDER BY e.expenseDate DESC, e.createdAt DESC")
    List<OwnerExpense> findByOwnerUserId(@Param("ownerId") String ownerId);

    /** P&amp;L feed — sum {@code total} per category for non-void rows
     *  in the date range. Same shape as the supplier-invoice query so
     *  the P&amp;L service can merge both into the byCategory map. */
    @Query("SELECT e.category, COALESCE(SUM(e.total), 0) FROM OwnerExpense e "
            + "WHERE e.status <> com.saffron.cashflow.domain.OwnerExpenseStatus.VOID "
            + "AND e.expenseDate BETWEEN :from AND :to "
            + "GROUP BY e.category")
    List<Object[]> sumByCategoryBetween(@Param("from") LocalDate from, @Param("to") LocalDate to);

    @Query("SELECT COALESCE(SUM(e.total - e.amountReimbursed), 0) FROM OwnerExpense e "
            + "WHERE e.status IN ("
            + "  com.saffron.cashflow.domain.OwnerExpenseStatus.PENDING, "
            + "  com.saffron.cashflow.domain.OwnerExpenseStatus.PARTIAL"
            + ") "
            + "AND e.ownerUserId = :ownerId")
    BigDecimal outstandingForOwner(@Param("ownerId") String ownerId);

    @Query("SELECT COUNT(e), COALESCE(SUM(e.total - e.amountReimbursed), 0) FROM OwnerExpense e "
            + "WHERE e.status IN ("
            + "  com.saffron.cashflow.domain.OwnerExpenseStatus.PENDING, "
            + "  com.saffron.cashflow.domain.OwnerExpenseStatus.PARTIAL"
            + ")")
    List<Object[]> countAndSumOutstanding();
}
