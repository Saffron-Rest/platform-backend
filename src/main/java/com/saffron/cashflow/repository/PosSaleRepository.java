package com.saffron.cashflow.repository;

import com.saffron.cashflow.domain.PosSale;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface PosSaleRepository extends JpaRepository<PosSale, String> {

    Optional<PosSale> findByIntegrationIdAndExternalId(String integrationId, String externalId);

    List<PosSale> findByBusinessDay(LocalDate businessDay);

    List<PosSale> findByBusinessDayBetween(LocalDate from, LocalDate to);

    @Query("select s from PosSale s where s.businessDay between :from and :to order by s.occurredAt asc")
    List<PosSale> findInRange(@Param("from") LocalDate from, @Param("to") LocalDate to);

    @Query("select count(s) from PosSale s where s.businessDay between :from and :to and s.menuItemId is null")
    long countUnmatchedInRange(@Param("from") LocalDate from, @Param("to") LocalDate to);

    long countByIntegrationId(String integrationId);

    long countByIntegrationIdAndReceivedAtAfter(String integrationId, Instant cutoff);

    List<PosSale> findTop5ByIntegrationIdOrderByReceivedAtDesc(String integrationId);

    @Query("select s from PosSale s where s.integrationId = :integrationId order by s.receivedAt desc")
    List<PosSale> findRecentByIntegrationId(@Param("integrationId") String integrationId,
                                            org.springframework.data.domain.Pageable pageable);
}
