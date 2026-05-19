package com.saffron.cashflow.repository;

import com.saffron.cashflow.domain.Alert;
import com.saffron.cashflow.domain.AlertType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface AlertRepository extends JpaRepository<Alert, String> {

    @Query("SELECT a FROM Alert a LEFT JOIN FETCH a.user WHERE a.id = :id")
    Optional<Alert> findByIdWithUser(String id);

    @Query("SELECT a FROM Alert a LEFT JOIN FETCH a.user ORDER BY a.createdAt DESC")
    List<Alert> findAllOrderByCreatedAtDesc();

    @Query("SELECT a FROM Alert a LEFT JOIN FETCH a.user WHERE a.user.id = :userId ORDER BY a.createdAt DESC")
    List<Alert> findByUserIdOrderByCreatedAtDesc(String userId);

    @Query("SELECT a FROM Alert a WHERE a.type = :type AND a.user.id = :userId AND a.createdAt >= :since")
    Optional<Alert> findFirstByTypeAndUserIdAndCreatedAtGreaterThanEqual(AlertType type, String userId, Instant since);
}
