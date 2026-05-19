package com.saffron.cashflow.repository;

import com.saffron.cashflow.domain.ExpenseItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.List;
import java.util.Optional;

public interface ExpenseItemRepository extends JpaRepository<ExpenseItem, String> {

    @Query("SELECT DISTINCT e FROM ExpenseItem e LEFT JOIN FETCH e.invoices WHERE e.entry.id = :entryId ORDER BY e.createdAt ASC")
    List<ExpenseItem> findByEntryIdWithInvoice(String entryId);

    @Query("SELECT e FROM ExpenseItem e WHERE e.id = :id AND e.entry.id = :entryId")
    Optional<ExpenseItem> findByIdAndEntryId(String id, String entryId);
}
