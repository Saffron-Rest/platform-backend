package com.saffron.cashflow.repository;

import com.saffron.cashflow.domain.UserTotp;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserTotpRepository extends JpaRepository<UserTotp, String> {
    Optional<UserTotp> findByUserId(String userId);
}
