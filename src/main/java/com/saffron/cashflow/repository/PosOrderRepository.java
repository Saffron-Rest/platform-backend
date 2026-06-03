package com.saffron.cashflow.repository;

import com.saffron.cashflow.domain.PosOrder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface PosOrderRepository extends JpaRepository<PosOrder, String> {

    @Query("SELECT o FROM PosOrder o WHERE o.status IN ('OPEN', 'PARKED') ORDER BY o.openedAt ASC")
    List<PosOrder> findAllOpen();

    @Query("SELECT o FROM PosOrder o WHERE o.tableId = :tableId AND o.status IN ('OPEN', 'PARKED') ORDER BY o.openedAt DESC")
    List<PosOrder> findActiveByTableId(@org.springframework.data.repository.query.Param("tableId") String tableId);

    @Query("SELECT o FROM PosOrder o WHERE o.cashierId = :cashierId AND o.openedAt >= :since ORDER BY o.openedAt DESC")
    List<PosOrder> findByCashierSince(@Param("cashierId") String cashierId, @Param("since") Instant since);

    @Query("SELECT o FROM PosOrder o WHERE o.status = 'PAID' AND o.paidAt >= :from AND o.paidAt <= :to ORDER BY o.paidAt ASC")
    List<PosOrder> findPaidBetween(@Param("from") Instant from, @Param("to") Instant to);
}
