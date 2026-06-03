package com.saffron.cashflow.repository;

import com.saffron.cashflow.domain.PosOrderPayment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PosOrderPaymentRepository extends JpaRepository<PosOrderPayment, String> {
    List<PosOrderPayment> findByOrderIdOrderByProcessedAtAsc(String orderId);
}
