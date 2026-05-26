package com.saffron.cashflow.controller;

import com.saffron.cashflow.service.ChecklistService;
import com.saffron.cashflow.service.FileStorageService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/checklists")
public class ChecklistController {

    private final ChecklistService checklistService;
    private final FileStorageService fileStorageService;

    public ChecklistController(ChecklistService checklistService, FileStorageService fileStorageService) {
        this.checklistService = checklistService;
        this.fileStorageService = fileStorageService;
    }

    // ---------- Templates ----------
    @GetMapping("/templates")
    public List<Map<String, Object>> listTemplates(
            @RequestParam(name = "includeArchived", defaultValue = "false") boolean includeArchived) {
        return checklistService.listTemplates(includeArchived);
    }

    @GetMapping("/templates/{id}")
    public Map<String, Object> getTemplate(@PathVariable String id) {
        return checklistService.getTemplate(id);
    }

    @PostMapping("/templates")
    public Map<String, Object> createTemplate(@RequestBody Map<String, Object> body) {
        return checklistService.createTemplate(body);
    }

    @PutMapping("/templates/{id}")
    public Map<String, Object> updateTemplate(@PathVariable String id, @RequestBody Map<String, Object> body) {
        return checklistService.updateTemplate(id, body);
    }

    @DeleteMapping("/templates/{id}")
    public Map<String, String> archiveTemplate(@PathVariable String id) {
        checklistService.deleteTemplate(id);
        return Map.of("archived", id);
    }

    // ---------- Runs ----------
    @GetMapping("/today")
    public List<Map<String, Object>> today(@RequestParam(required = false) String date) {
        LocalDate d = date == null || date.isBlank() ? null : LocalDate.parse(date);
        return checklistService.todayRuns(d);
    }

    @GetMapping("/history")
    public List<Map<String, Object>> history(@RequestParam(defaultValue = "14") int days) {
        return checklistService.history(days);
    }

    @PostMapping("/runs/{templateId}")
    public Map<String, Object> upsertRun(@PathVariable String templateId, @RequestBody Map<String, Object> body) {
        return checklistService.upsertRun(templateId, body);
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, String> upload(@RequestParam("file") MultipartFile file) throws IOException {
        String path = fileStorageService.storeUnderPrefix(file, "checklist");
        return Map.of("path", path);
    }
}
