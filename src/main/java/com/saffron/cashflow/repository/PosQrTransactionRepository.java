package com.saffron.cashflow.repository;

import com.saffron.cashflow.domain.PosQrTransaction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PosQrTransactionRepository extends JpaRepository<PosQrTransaction, String> {
    List<PosQrTransaction> findByOrderIdOrderByCreatedAtDesc(String orderId);
}
