package com.saffron.cashflow.repository;

import com.saffron.cashflow.domain.CashDrawerTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;

public interface CashDrawerTransactionRepository extends JpaRepository<CashDrawerTransaction, String> {

    List<CashDrawerTransaction> findBySessionIdOrderByCreatedAtAsc(String sessionId);

    @Query("SELECT COALESCE(SUM(CASE WHEN t.type = 'IN' THEN t.amount ELSE -t.amount END), 0) " +
           "FROM CashDrawerTransaction t WHERE t.sessionId = :sessionId")
    BigDecimal netMovementForSession(@Param("sessionId") String sessionId);
}
