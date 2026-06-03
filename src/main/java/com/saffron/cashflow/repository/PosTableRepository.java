package com.saffron.cashflow.repository;

import com.saffron.cashflow.domain.PosTable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PosTableRepository extends JpaRepository<PosTable, String> {
    List<PosTable> findByActiveTrueOrderByAreaAscGridYAscGridXAsc();
}
