package com.saffron.cashflow.controller;

import com.saffron.cashflow.service.EmployeeCertService;
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
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/certifications")
public class EmployeeCertController {

    private final EmployeeCertService certService;
    private final FileStorageService fileStorageService;

    public EmployeeCertController(EmployeeCertService certService, FileStorageService fileStorageService) {
        this.certService = certService;
        this.fileStorageService = fileStorageService;
    }

    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(required = false) String userId) {
        return certService.list(userId);
    }

    @GetMapping("/types")
    public List<String> types() {
        return EmployeeCertService.SUGGESTED_TYPES;
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable String id) {
        return certService.get(id);
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody Map<String, Object> body) {
        return certService.create(body);
    }

    @PutMapping("/{id}")
    public Map<String, Object> update(@PathVariable String id, @RequestBody Map<String, Object> body) {
        return certService.update(id, body);
    }

    @DeleteMapping("/{id}")
    public Map<String, String> delete(@PathVariable String id) {
        certService.delete(id);
        return Map.of("deleted", id);
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, String> upload(@RequestParam("file") MultipartFile file) throws IOException {
        String path = fileStorageService.storeUnderPrefix(file, "cert");
        return Map.of("path", path);
    }
}
