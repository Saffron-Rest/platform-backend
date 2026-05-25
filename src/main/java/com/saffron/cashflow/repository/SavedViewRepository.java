package com.saffron.cashflow.repository;

import com.saffron.cashflow.domain.SavedView;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;

public interface SavedViewRepository extends JpaRepository<SavedView, String> {

    List<SavedView> findByUserIdAndPageOrderByNameAsc(String userId, String page);

    Optional<SavedView> findByUserIdAndPageAndName(String userId, String page, String name);

    @Modifying
    @Query("UPDATE SavedView v SET v.isDefault = false WHERE v.userId = :userId AND v.page = :page AND v.id <> :keep")
    void clearOtherDefaults(@Param("userId") String userId, @Param("page") String page, @Param("keep") String keep);
}
