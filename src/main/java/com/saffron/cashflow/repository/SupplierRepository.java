package com.saffron.cashflow.repository;

import com.saffron.cashflow.domain.Supplier;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface SupplierRepository extends JpaRepository<Supplier, String> {

    @Query("SELECT s FROM Supplier s ORDER BY LOWER(s.name) ASC")
    List<Supplier> findAllOrdered();

    @Query("SELECT s FROM Supplier s WHERE s.active = true ORDER BY LOWER(s.name) ASC")
    List<Supplier> findActiveOrdered();

    @Query("SELECT s FROM Supplier s WHERE LOWER(s.name) = LOWER(:name)")
    Optional<Supplier> findByNameIgnoreCase(String name);
}
