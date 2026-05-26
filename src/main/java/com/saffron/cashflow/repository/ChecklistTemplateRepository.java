package com.saffron.cashflow.repository;

import com.saffron.cashflow.domain.ChecklistTemplate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ChecklistTemplateRepository extends JpaRepository<ChecklistTemplate, String> {

    /** Active templates ordered by type (OPENING first) then name. */
    List<ChecklistTemplate> findAllByOrderByActiveDescTypeAscNameAsc();

    List<ChecklistTemplate> findByActiveTrueOrderByTypeAscNameAsc();
}
