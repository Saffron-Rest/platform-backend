package com.saffron.cashflow.repository;

import com.saffron.cashflow.domain.BankDepositLink;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface BankDepositLinkRepository extends JpaRepository<BankDepositLink, String> {

    Optional<BankDepositLink> findByLinkedKindAndLinkedRefId(String linkedKind, String linkedRefId);
}
