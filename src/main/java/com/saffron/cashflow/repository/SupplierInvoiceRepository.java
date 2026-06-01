package com.saffron.cashflow.repository;

import com.saffron.cashflow.domain.ExpenseCategory;
import com.saffron.cashflow.domain.SupplierInvoice;
import com.saffron.cashflow.domain.SupplierInvoiceStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface SupplierInvoiceRepository extends JpaRepository<SupplierInvoice, String> {

    /** Active (non-void) invoices in date range, newest first. */
    @Query("SELECT i FROM SupplierInvoice i "
            + "WHERE i.status <> com.saffron.cashflow.domain.SupplierInvoiceStatus.VOID "
            + "AND i.invoiceDate BETWEEN :from AND :to "
            + "ORDER BY i.invoiceDate DESC, i.createdAt DESC")
    List<SupplierInvoice> findActiveBetween(@Param("from") LocalDate from, @Param("to") LocalDate to);

    /** All invoices (including voids) — admin views. */
    @Query("SELECT i FROM SupplierInvoice i ORDER BY i.invoiceDate DESC, i.createdAt DESC")
    List<SupplierInvoice> findAllOrdered();

    @Query("SELECT i FROM SupplierInvoice i WHERE i.status IN :statuses "
            + "ORDER BY i.dueDate ASC, i.invoiceDate ASC")
    List<SupplierInvoice> findByStatuses(@Param("statuses") List<SupplierInvoiceStatus> statuses);

    /**
     * P&amp;L feed — sum {@code total} per category for non-void
     * invoices in the date range. Returns rows {@code [category, sum]}.
     */
    @Query("SELECT i.category, COALESCE(SUM(i.total), 0) FROM SupplierInvoice i "
            + "WHERE i.status <> com.saffron.cashflow.domain.SupplierInvoiceStatus.VOID "
            + "AND i.invoiceDate BETWEEN :from AND :to "
            + "GROUP BY i.category")
    List<Object[]> sumByCategoryBetween(@Param("from") LocalDate from, @Param("to") LocalDate to);

    /** Subset for an explicit category (used to back-fill the COGS line
     *  if an environment has no SUPPLIES invoices). */
    @Query("SELECT COALESCE(SUM(i.total), 0) FROM SupplierInvoice i "
            + "WHERE i.status <> com.saffron.cashflow.domain.SupplierInvoiceStatus.VOID "
            + "AND i.category = :category "
            + "AND i.invoiceDate BETWEEN :from AND :to")
    java.math.BigDecimal sumCategoryBetween(
            @Param("category") ExpenseCategory category,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to);

    @Query("SELECT i FROM SupplierInvoice i WHERE i.supplier.id = :supplierId "
            + "ORDER BY i.invoiceDate DESC, i.createdAt DESC")
    List<SupplierInvoice> findBySupplierId(@Param("supplierId") String supplierId);
}
