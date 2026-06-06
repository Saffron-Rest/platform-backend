package com.saffron.cashflow.repository;

import com.saffron.cashflow.domain.PosWebhookLog;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PosWebhookLogRepository extends JpaRepository<PosWebhookLog, String> {

    @Query("select l from PosWebhookLog l where l.integrationId = :integrationId order by l.receivedAt desc")
    List<PosWebhookLog> findRecentByIntegrationId(@Param("integrationId") String integrationId, Pageable pageable);

    Optional<PosWebhookLog> findFirstByIntegrationIdAndExternalId(String integrationId, String externalId);
}
