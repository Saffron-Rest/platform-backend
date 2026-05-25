package com.saffron.cashflow.controller;

import com.saffron.cashflow.dto.TagAssignmentRequest;
import com.saffron.cashflow.dto.TagBulkAssignmentRequest;
import com.saffron.cashflow.dto.TagReplaceRequest;
import com.saffron.cashflow.dto.TagRequest;
import com.saffron.cashflow.service.TagService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/tags")
public class TagController {

    private final TagService tagService;

    public TagController(TagService tagService) {
        this.tagService = tagService;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return tagService.listTags();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(@Valid @RequestBody TagRequest req) {
        return tagService.createTag(req.name(), req.color(), req.description());
    }

    @PutMapping("/{id}")
    public Map<String, Object> update(@PathVariable String id, @Valid @RequestBody TagRequest req) {
        return tagService.updateTag(id, req.name(), req.color(), req.description());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) {
        tagService.deleteTag(id);
    }

    @PostMapping("/{id}/assign")
    public Map<String, Object> assign(@PathVariable String id, @Valid @RequestBody TagAssignmentRequest req) {
        tagService.assign(id, req.entityType(), req.entityId());
        return Map.of("ok", true);
    }

    @PostMapping("/{id}/unassign")
    public Map<String, Object> unassign(@PathVariable String id, @Valid @RequestBody TagAssignmentRequest req) {
        tagService.unassign(id, req.entityType(), req.entityId());
        return Map.of("ok", true);
    }

    @PostMapping("/{id}/bulk-assign")
    public Map<String, Object> bulkAssign(@PathVariable String id, @Valid @RequestBody TagBulkAssignmentRequest req) {
        tagService.bulkAssign(id, req.entityType(), req.entityIds());
        return Map.of("ok", true, "count", req.entityIds().size());
    }

    /** Replace ALL tags on a record in one call — handy for the picker. */
    @PutMapping("/assignments")
    public Map<String, Object> replace(@Valid @RequestBody TagReplaceRequest req) {
        tagService.replace(req.entityType(), req.entityId(),
                req.tagIds() == null ? java.util.Set.of() : new java.util.HashSet<>(req.tagIds()));
        return Map.of("ok", true);
    }
}
