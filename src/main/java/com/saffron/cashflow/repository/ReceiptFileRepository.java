package com.saffron.cashflow.repository;

import com.saffron.cashflow.domain.ReceiptFile;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface ReceiptFileRepository extends JpaRepository<ReceiptFile, String> {
    Optional<ReceiptFile> findById(String id);

    /** Files directly attached to a shift entry (e.g. POS card report uploads). */
    List<ReceiptFile> findByEntry_IdOrderByCreatedAtAsc(String entryId);
}
