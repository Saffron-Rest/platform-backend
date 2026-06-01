package com.saffron.cashflow.repository;

import com.saffron.cashflow.domain.DailyEntry;
import com.saffron.cashflow.domain.EntryStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface DailyEntryRepository extends JpaRepository<DailyEntry, String>, JpaSpecificationExecutor<DailyEntry> {

    @Query("SELECT e FROM DailyEntry e LEFT JOIN FETCH e.cashier WHERE e.id = :id AND e.deletedAt IS NULL")
    Optional<DailyEntry> findActiveById(String id);

    @Query("SELECT e FROM DailyEntry e LEFT JOIN FETCH e.cashier LEFT JOIN FETCH e.files WHERE e.id = :id AND e.deletedAt IS NULL")
    Optional<DailyEntry> findActiveByIdWithFiles(String id);

    /** Expense lines only — invoice files loaded separately to avoid MultipleBagFetchException. */
    @Query("SELECT DISTINCT e FROM DailyEntry e LEFT JOIN FETCH e.cashier LEFT JOIN FETCH e.expenseItems WHERE e.id = :id AND e.deletedAt IS NULL")
    Optional<DailyEntry> findActiveByIdWithExpenses(String id);

    @Query("SELECT e FROM DailyEntry e WHERE e.cashier.id = :cashierId AND e.date = :date AND e.deletedAt IS NULL")
    Optional<DailyEntry> findByCashierIdAndDateAndDeletedAtIsNull(String cashierId, LocalDate date);

    /** Includes soft-deleted rows (unique key still applies per cashier + date). */
    Optional<DailyEntry> findByCashier_IdAndDate(String cashierId, LocalDate date);

    @Query("SELECT e FROM DailyEntry e JOIN FETCH e.cashier WHERE e.cashier.id = :cashierId AND e.date = :date AND e.deletedAt IS NULL")
    Optional<DailyEntry> findByCashierIdAndDateWithCashier(String cashierId, LocalDate date);

    boolean existsByCashier_IdAndDate(String cashierId, LocalDate date);

    @Query("SELECT e FROM DailyEntry e LEFT JOIN FETCH e.cashier WHERE e.date = :date AND e.deletedAt IS NULL")
    List<DailyEntry> findByDateAndDeletedAtIsNull(LocalDate date);

    @Query("SELECT e FROM DailyEntry e LEFT JOIN FETCH e.cashier WHERE e.date BETWEEN :from AND :to AND e.deletedAt IS NULL AND e.status = :status ORDER BY e.date ASC")
    List<DailyEntry> findLockedBetween(LocalDate from, LocalDate to, EntryStatus status);

    @Query("""
            SELECT DISTINCT e FROM DailyEntry e
            LEFT JOIN FETCH e.expenseItems
            WHERE e.date BETWEEN :from AND :to AND e.deletedAt IS NULL AND e.status = :status
            """)
    List<DailyEntry> findLockedBetweenWithExpenses(LocalDate from, LocalDate to, EntryStatus status);

    @Query("SELECT e FROM DailyEntry e LEFT JOIN FETCH e.cashier WHERE e.date BETWEEN :from AND :to AND e.deletedAt IS NULL AND e.status = :status AND e.cashier.id = :cashierId ORDER BY e.date ASC")
    List<DailyEntry> findLockedBetweenForCashier(LocalDate from, LocalDate to, EntryStatus status, String cashierId);

    @Query("SELECT COUNT(e) > 0 FROM DailyEntry e WHERE e.cashier.id = :cashierId AND e.date = :date AND e.deletedAt IS NULL AND e.status = :status")
    boolean existsByCashierIdAndDateAndDeletedAtIsNullAndStatus(String cashierId, LocalDate date, EntryStatus status);

    Optional<DailyEntry> findTop1ByCashier_IdAndDateLessThanAndDeletedAtIsNullAndStatusOrderByDateDesc(
            String cashierId, LocalDate date, EntryStatus status);

    /**
     * All non-deleted reports by this cashier inside the inclusive range,
     * newest first. Used by the shift-create gap check to find the most
     * recent prior shift while skipping over closure days.
     */
    @Query("""
            SELECT e FROM DailyEntry e
            WHERE e.cashier.id = :cashierId
              AND e.date BETWEEN :from AND :to
              AND e.deletedAt IS NULL
            ORDER BY e.date DESC
            """)
    List<DailyEntry> findActiveByCashierBetweenDesc(String cashierId, LocalDate from, LocalDate to);

    /** Most recent calendar day before {@code before} with a locked actual cash count (any cashier). */
    @Query("""
            SELECT MAX(e.date) FROM DailyEntry e
            WHERE e.date < :before AND e.deletedAt IS NULL AND e.status = :status
            AND e.actualCashCounted > 0
            """)
    Optional<LocalDate> findLatestRestaurantCloseDateBefore(LocalDate before, EntryStatus status);

    /** Latest locked count on a given day (e.g. closing shift) — any cashier. */
    @Query("""
            SELECT e FROM DailyEntry e JOIN FETCH e.cashier
            WHERE e.date = :closeDate AND e.deletedAt IS NULL AND e.status = :status
            AND e.actualCashCounted > 0
            ORDER BY e.submittedAt DESC, e.updatedAt DESC
            """)
    List<DailyEntry> findLatestRestaurantCloseOnDate(LocalDate closeDate, EntryStatus status, Pageable pageable);

    /** Latest same-day actual count from another cashier (restaurant drawer handoff). */
    @Query("""
            SELECT e FROM DailyEntry e JOIN FETCH e.cashier
            WHERE e.date = :date AND e.deletedAt IS NULL
            AND e.cashier.id <> :excludeCashierId
            AND e.actualCashCounted > 0
            ORDER BY CASE WHEN e.status = :locked THEN 1 ELSE 0 END DESC,
                     e.submittedAt DESC, e.updatedAt DESC
            """)
    List<DailyEntry> findLatestSameDayRestaurantCount(
            String excludeCashierId, LocalDate date, EntryStatus locked, Pageable pageable);
}
