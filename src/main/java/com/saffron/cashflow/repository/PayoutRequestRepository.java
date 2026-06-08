package com.saffron.cashflow.repository;

import com.saffron.cashflow.domain.PayoutRequest;
import com.saffron.cashflow.domain.PayoutRequestStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface PayoutRequestRepository extends JpaRepository<PayoutRequest, String> {

    @Query("SELECT r FROM PayoutRequest r WHERE r.userId = :userId ORDER BY r.createdAt DESC")
    List<PayoutRequest> findByUserId(@Param("userId") String userId);

    @Query("SELECT r FROM PayoutRequest r WHERE r.status = :status ORDER BY r.createdAt ASC")
    List<PayoutRequest> findByStatus(@Param("status") PayoutRequestStatus status);

    @Query("SELECT r FROM PayoutRequest r ORDER BY r.createdAt DESC")
    List<PayoutRequest> findAllOrdered();

    boolean existsByUserIdAndStatus(String userId, PayoutRequestStatus status);
}
