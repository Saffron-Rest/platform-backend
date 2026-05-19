package com.saffron.cashflow.repository;

import com.saffron.cashflow.domain.ExpenseItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface ExpenseItemRepository extends JpaRepository<ExpenseItem, String> {

    @Query("SELECT DISTINCT e FROM ExpenseItem e LEFT JOIN FETCH e.invoices WHERE e.entry.id = :entryId ORDER BY e.createdAt ASC")
    List<ExpenseItem> findByEntryIdWithInvoice(String entryId);

    @Query("SELECT e FROM ExpenseItem e WHERE e.id = :id AND e.entry.id = :entryId")
    Optional<ExpenseItem> findByIdAndEntryId(String id, String entryId);

    @Query("SELECT DISTINCT e FROM ExpenseItem e LEFT JOIN FETCH e.invoices WHERE e.id = :id")
    Optional<ExpenseItem> findByIdWithInvoices(String id);

    @Query("SELECT DISTINCT e FROM ExpenseItem e LEFT JOIN FETCH e.invoices WHERE e.id = :id AND e.entry.id = :entryId")
    Optional<ExpenseItem> findByIdAndEntryIdWithInvoices(String id, String entryId);

    @Query("""
            SELECT DISTINCT e FROM ExpenseItem e
            LEFT JOIN FETCH e.invoices
            LEFT JOIN FETCH e.entry
            WHERE e.effectiveDate BETWEEN :from AND :to
            ORDER BY e.effectiveDate DESC, e.createdAt DESC
            """)
    List<ExpenseItem> findByEffectiveDateBetweenWithInvoices(LocalDate from, LocalDate to);

    @Query("""
            SELECT DISTINCT e FROM ExpenseItem e
            LEFT JOIN FETCH e.invoices
            WHERE e.entry IS NULL AND e.effectiveDate BETWEEN :from AND :to
            ORDER BY e.effectiveDate DESC, e.createdAt DESC
            """)
    List<ExpenseItem> findStandaloneBetweenWithInvoices(LocalDate from, LocalDate to);
}
