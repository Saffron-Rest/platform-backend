package com.saffron.cashflow.repository;

import com.saffron.cashflow.domain.EmployeeCert;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface EmployeeCertRepository extends JpaRepository<EmployeeCert, String> {

    /** All certs, optionally restricted to a user. Ordered by expiry asc so
     *  the most urgent always floats to the top of the admin page. */
    @Query("SELECT c FROM EmployeeCert c "
            + "WHERE (:userId IS NULL OR c.userId = :userId) "
            + "ORDER BY CASE WHEN c.expiresOn IS NULL THEN 1 ELSE 0 END, c.expiresOn ASC, LOWER(c.type) ASC")
    List<EmployeeCert> findOrdered(@Param("userId") String userId);

    /** Used by the daily expiry-reminder job. Returns certs with a known
     *  expiry on or before the given date. */
    @Query("SELECT c FROM EmployeeCert c WHERE c.expiresOn IS NOT NULL "
            + "AND c.expiresOn <= :horizon ORDER BY c.expiresOn ASC")
    List<EmployeeCert> findExpiringBy(@Param("horizon") LocalDate horizon);
}
