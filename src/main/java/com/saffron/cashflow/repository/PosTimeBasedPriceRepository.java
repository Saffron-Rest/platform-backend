package com.saffron.cashflow.repository;

import com.saffron.cashflow.domain.PosTimeBasedPrice;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PosTimeBasedPriceRepository extends JpaRepository<PosTimeBasedPrice, String> {
    List<PosTimeBasedPrice> findByMenuItemIdAndActiveTrue(String menuItemId);
    List<PosTimeBasedPrice> findAllByActiveTrue();
}
