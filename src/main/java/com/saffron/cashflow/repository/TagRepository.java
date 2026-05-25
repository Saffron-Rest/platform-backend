package com.saffron.cashflow.repository;

import com.saffron.cashflow.domain.Tag;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface TagRepository extends JpaRepository<Tag, String> {

    List<Tag> findAllByOrderByNameAsc();

    /** Case-insensitive name lookup — used to prevent duplicate tags like
     *  "Investigate" / "investigate" / "INVESTIGATE". */
    Optional<Tag> findFirstByNameIgnoreCase(String name);
}
