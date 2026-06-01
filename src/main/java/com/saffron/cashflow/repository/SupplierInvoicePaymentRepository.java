package com.saffron.cashflow.repository;

import com.saffron.cashflow.domain.SupplierInvoicePayment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface SupplierInvoicePaymentRepository extends JpaRepository<SupplierInvoicePayment, String> {

    @Query("SELECT p FROM SupplierInvoicePayment p WHERE p.invoice.id = :invoiceId "
            + "ORDER BY p.paymentDate DESC, p.createdAt DESC")
    List<SupplierInvoicePayment> findByInvoiceId(@Param("invoiceId") String invoiceId);

    @Query("SELECT p FROM SupplierInvoicePayment p WHERE p.paymentDate BETWEEN :from AND :to "
            + "ORDER BY p.paymentDate DESC, p.createdAt DESC")
    List<SupplierInvoicePayment> findByPaymentDateBetween(
            @Param("from") LocalDate from, @Param("to") LocalDate to);
}
