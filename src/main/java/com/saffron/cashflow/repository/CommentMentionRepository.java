package com.saffron.cashflow.repository;

import com.saffron.cashflow.domain.CommentMention;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface CommentMentionRepository extends JpaRepository<CommentMention, String> {

    List<CommentMention> findByCommentId(String commentId);

    void deleteByCommentId(String commentId);
}
