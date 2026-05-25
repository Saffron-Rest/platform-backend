package com.saffron.cashflow.controller;

import com.saffron.cashflow.domain.TaggedEntityType;
import com.saffron.cashflow.service.CommentService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/comments")
public class CommentController {

    private final CommentService commentService;

    public CommentController(CommentService commentService) {
        this.commentService = commentService;
    }

    @GetMapping
    public List<Map<String, Object>> list(
            @RequestParam TaggedEntityType entityType,
            @RequestParam String entityId) {
        return commentService.list(entityType, entityId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(@Valid @RequestBody CreateCommentRequest req) {
        return commentService.create(req.entityType(), req.entityId(), req.body());
    }

    @PutMapping("/{id}")
    public Map<String, Object> update(@PathVariable String id, @Valid @RequestBody UpdateCommentRequest req) {
        return commentService.update(id, req.body());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) {
        commentService.delete(id);
    }

    public record CreateCommentRequest(
            @NotNull TaggedEntityType entityType,
            @NotBlank String entityId,
            @NotBlank String body) {}

    public record UpdateCommentRequest(@NotBlank String body) {}
}
