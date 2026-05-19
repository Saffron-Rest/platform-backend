package com.saffron.cashflow.controller;

import com.saffron.cashflow.service.FileStorageService;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import com.saffron.cashflow.domain.ReceiptFile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/files")
public class FileController {

    private final FileStorageService fileStorageService;

    public FileController(FileStorageService fileStorageService) {
        this.fileStorageService = fileStorageService;
    }

    @PostMapping("/{entryId}")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> upload(
            @PathVariable String entryId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(required = false) String category) throws Exception {
        return fileStorageService.upload(entryId, file, category);
    }

    @GetMapping("/{entryId}")
    public List<Map<String, Object>> list(@PathVariable String entryId) {
        return fileStorageService.listForEntry(entryId);
    }

    @GetMapping("/download/{fileId}")
    public ResponseEntity<Resource> download(@PathVariable String fileId) throws Exception {
        ReceiptFile meta = fileStorageService.resolveReceiptFile(fileId);
        Path path = fileStorageService.resolveFile(fileId);
        Resource resource = new UrlResource(path.toUri());
        String filename = meta.getFilename() != null ? meta.getFilename() : "invoice";
        return ResponseEntity.ok()
                .contentType(contentTypeForFilename(filename))
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + filename.replace("\"", "") + "\"")
                .body(resource);
    }

    private static MediaType contentTypeForFilename(String filename) {
        String lower = filename.toLowerCase();
        if (lower.endsWith(".png")) return MediaType.IMAGE_PNG;
        if (lower.endsWith(".webp")) return MediaType.parseMediaType("image/webp");
        if (lower.endsWith(".gif")) return MediaType.IMAGE_GIF;
        if (lower.endsWith(".pdf")) return MediaType.APPLICATION_PDF;
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return MediaType.IMAGE_JPEG;
        return MediaType.APPLICATION_OCTET_STREAM;
    }
}
