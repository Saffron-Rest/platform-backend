package com.saffron.cashflow.repository;

import com.saffron.cashflow.domain.TagAssignment;
import com.saffron.cashflow.domain.TaggedEntityType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;

public interface TagAssignmentRepository extends JpaRepository<TagAssignment, String> {

    List<TagAssignment> findByEntityTypeAndEntityId(TaggedEntityType entityType, String entityId);

    /** Bulk fetch — single query when mapping a list of records to their tags. */
    @Query("""
            SELECT a FROM TagAssignment a
            WHERE a.entityType = :entityType
              AND a.entityId IN :entityIds
            """)
    List<TagAssignment> findByEntityTypeAndEntityIdIn(
            @Param("entityType") TaggedEntityType entityType,
            @Param("entityIds") List<String> entityIds);

    Optional<TagAssignment> findByTagIdAndEntityTypeAndEntityId(
            String tagId, TaggedEntityType entityType, String entityId);

    /** All (entityType, entityId) pairs that carry the given tag. Used by
     *  list filters: "show only records tagged X". */
    @Query("""
            SELECT a.entityId FROM TagAssignment a
            WHERE a.tagId IN :tagIds
              AND a.entityType = :entityType
            """)
    List<String> findEntityIdsByTagIdsAndEntityType(
            @Param("tagIds") List<String> tagIds,
            @Param("entityType") TaggedEntityType entityType);

    long countByTagId(String tagId);

    void deleteByTagId(String tagId);

    void deleteByEntityTypeAndEntityId(TaggedEntityType entityType, String entityId);
}
