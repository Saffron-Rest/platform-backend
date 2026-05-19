package com.saffron.cashflow.repository;

import com.saffron.cashflow.domain.CashierNotificationType;
import com.saffron.cashflow.domain.NotificationDispatch;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface NotificationDispatchRepository extends JpaRepository<NotificationDispatch, String> {

    Optional<NotificationDispatch> findByUserIdAndTypeAndReferenceDate(
            String userId, CashierNotificationType type, LocalDate referenceDate);

    @Query("SELECT n FROM NotificationDispatch n WHERE n.userId = :userId ORDER BY n.sentAt DESC")
    List<NotificationDispatch> findByUserIdOrderBySentAtDesc(String userId);
}
