package com.saffron.cashflow.repository;

import com.saffron.cashflow.domain.Comment;
import com.saffron.cashflow.domain.TaggedEntityType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;

public interface CommentRepository extends JpaRepository<Comment, String> {

    @Query("""
            SELECT c FROM Comment c
            WHERE c.entityType = :entityType
              AND c.entityId = :entityId
              AND c.deletedAt IS NULL
            ORDER BY c.createdAt ASC
            """)
    List<Comment> findActiveByEntity(
            @Param("entityType") TaggedEntityType entityType,
            @Param("entityId") String entityId);

    @Query("""
            SELECT c.entityId, COUNT(c) FROM Comment c
            WHERE c.entityType = :entityType
              AND c.entityId IN :entityIds
              AND c.deletedAt IS NULL
            GROUP BY c.entityId
            """)
    List<Object[]> countActiveByEntities(
            @Param("entityType") TaggedEntityType entityType,
            @Param("entityIds") List<String> entityIds);
}
