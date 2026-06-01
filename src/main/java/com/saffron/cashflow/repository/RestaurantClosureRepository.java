package com.saffron.cashflow.repository;

import com.saffron.cashflow.domain.RestaurantClosure;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

public interface RestaurantClosureRepository extends JpaRepository<RestaurantClosure, LocalDate> {

    /** Sorted by date ascending — UI calendar display. */
    List<RestaurantClosure> findAllByOrderByDateAsc();

    /** Closures that fall in the inclusive range — used by the shift-create
     *  gap check to skip closed days when walking back through history. */
    List<RestaurantClosure> findByDateBetweenOrderByDateAsc(LocalDate from, LocalDate to);

    /** Convenience: just the dates inside the range, hashable for fast
     *  membership tests by the gap check. */
    default Set<LocalDate> dateSetBetween(LocalDate from, LocalDate to) {
        return findByDateBetweenOrderByDateAsc(from, to).stream()
                .map(RestaurantClosure::getDate)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
}
