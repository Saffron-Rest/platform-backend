package com.saffron.cashflow.controller;

import com.saffron.cashflow.service.FileStorageService;
import com.saffron.cashflow.service.IncidentService;
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
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/incidents")
public class IncidentController {

    private final IncidentService incidentService;
    private final FileStorageService fileStorageService;

    public IncidentController(IncidentService incidentService, FileStorageService fileStorageService) {
        this.incidentService = incidentService;
        this.fileStorageService = fileStorageService;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return incidentService.list();
    }

    @GetMapping("/counts")
    public Map<String, Long> counts() {
        return incidentService.counts();
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable String id) {
        return incidentService.get(id);
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody Map<String, Object> body) {
        return incidentService.create(body);
    }

    @PutMapping("/{id}")
    public Map<String, Object> update(@PathVariable String id, @RequestBody Map<String, Object> body) {
        return incidentService.update(id, body);
    }

    @PostMapping("/{id}/resolve")
    public Map<String, Object> resolve(@PathVariable String id, @RequestBody Map<String, Object> body) {
        return incidentService.resolve(id, body);
    }

    @PostMapping("/{id}/reopen")
    public Map<String, Object> reopen(@PathVariable String id) {
        return incidentService.reopen(id);
    }

    @DeleteMapping("/{id}")
    public Map<String, String> delete(@PathVariable String id) {
        incidentService.delete(id);
        return Map.of("deleted", id);
    }

    /**
     * Photo attachment endpoint. Returns the relative path which callers
     * stash on the incident via PUT {@code /{id}} with body {@code photoPath: ...}.
     * Mounted separately so the page can upload before the incident is
     * even saved (for the "file new" flow).
     */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, String> uploadPhoto(@RequestParam("file") MultipartFile file) throws IOException {
        String path = fileStorageService.storeUnderPrefix(file, "incident");
        return Map.of("path", path);
    }
}
