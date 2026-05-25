package com.saffron.cashflow.repository;

import com.saffron.cashflow.domain.PosIntegration;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PosIntegrationRepository extends JpaRepository<PosIntegration, String> {

    List<PosIntegration> findAllByOrderByNameAsc();

    Optional<PosIntegration> findFirstByNameIgnoreCase(String name);
}
