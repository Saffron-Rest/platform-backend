package com.saffron.cashflow.repository;

import com.saffron.cashflow.domain.Role;
import com.saffron.cashflow.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, String> {
    Optional<User> findByEmail(String email);

    Optional<User> findByUsername(String username);

    boolean existsByUsername(String username);

    List<User> findByRoleAndActiveTrue(Role role);
}
