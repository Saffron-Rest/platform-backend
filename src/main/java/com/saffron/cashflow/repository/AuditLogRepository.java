package com.saffron.cashflow.repository;

import com.saffron.cashflow.domain.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import java.util.List;
import java.util.Optional;

public interface AuditLogRepository extends JpaRepository<AuditLog, String>, JpaSpecificationExecutor<AuditLog> {

    @Query(
            value = "SELECT a FROM AuditLog a LEFT JOIN FETCH a.user",
            countQuery = "SELECT COUNT(a) FROM AuditLog a")
    Page<AuditLog> findAllWithUser(Pageable pageable);

    @Query("SELECT a FROM AuditLog a LEFT JOIN FETCH a.user WHERE a.id = :id")
    Optional<AuditLog> findByIdWithUser(String id);

    /** Used by global search — restrict to recent entries so we don't scan
     *  the whole table for ad-hoc queries. */
    List<AuditLog> findTop100ByOrderByCreatedAtDesc();
}
