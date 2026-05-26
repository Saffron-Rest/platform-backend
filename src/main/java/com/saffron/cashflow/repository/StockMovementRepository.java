package com.saffron.cashflow.repository;

import com.saffron.cashflow.domain.StockMovement;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface StockMovementRepository extends JpaRepository<StockMovement, String> {

    /** Most-recent-first history for one item. Used by the drawer in the UI. */
    List<StockMovement> findByStockItemIdOrderByCreatedAtDesc(String stockItemId);

    /** Idempotency for POS sale ingest — looks up an existing decrement
     *  by (referenceType = "POS_SALE", referenceId = posSale.id). The
     *  partial unique index on the table guarantees at most one row. */
    Optional<StockMovement> findFirstByReferenceTypeAndReferenceId(String referenceType, String referenceId);
}
