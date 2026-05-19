package com.saffron.cashflow.repository;

import com.saffron.cashflow.domain.ReceiptFile;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface ReceiptFileRepository extends JpaRepository<ReceiptFile, String> {
    Optional<ReceiptFile> findById(String id);
}
