package com.saffron.cashflow.repository;

import com.saffron.cashflow.domain.AdminTelegramDispatch;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface AdminTelegramDispatchRepository extends JpaRepository<AdminTelegramDispatch, String> {

    boolean existsByDedupeKey(String dedupeKey);

    Optional<AdminTelegramDispatch> findByDedupeKey(String dedupeKey);
}
