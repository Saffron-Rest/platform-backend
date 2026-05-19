package com.saffron.cashflow.repository;

import com.saffron.cashflow.domain.SalaryPayment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.time.LocalDate;
import java.util.List;

public interface SalaryPaymentRepository extends JpaRepository<SalaryPayment, String> {

    @Query("SELECT p FROM SalaryPayment p WHERE p.paidDate BETWEEN :from AND :to ORDER BY p.paidDate DESC, p.createdAt DESC")
    List<SalaryPayment> findByPaidDateBetween(LocalDate from, LocalDate to);

    List<SalaryPayment> findAllByOrderByPaidDateDescCreatedAtDesc();
}
