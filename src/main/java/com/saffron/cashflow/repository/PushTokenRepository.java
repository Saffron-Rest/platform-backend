package com.saffron.cashflow.repository;

import com.saffron.cashflow.domain.PushToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.List;
import java.util.Optional;

public interface PushTokenRepository extends JpaRepository<PushToken, String> {

    Optional<PushToken> findByExpoPushToken(String expoPushToken);

    @Query("SELECT t FROM PushToken t WHERE t.user.id = :userId")
    List<PushToken> findByUserId(String userId);
}
