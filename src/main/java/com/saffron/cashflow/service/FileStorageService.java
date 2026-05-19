package com.saffron.cashflow.service;

import com.saffron.cashflow.domain.DailyEntry;
import com.saffron.cashflow.domain.EntryStatus;
import com.saffron.cashflow.domain.ReceiptFile;
import com.saffron.cashflow.domain.Role;
import com.saffron.cashflow.repository.DailyEntryRepository;
import com.saffron.cashflow.repository.ReceiptFileRepository;
import com.saffron.cashflow.security.AuthHelper;
import com.saffron.cashflow.security.AuthUser;
import com.saffron.cashflow.security.ForbiddenException;
import com.saffron.cashflow.web.NotFoundException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class FileStorageService {

    private final Path uploadDir;
    private final DailyEntryRepository entryRepository;
    private final ReceiptFileRepository fileRepository;

    public FileStorageService(
            @Value("${app.upload-dir}") String uploadDir,
            DailyEntryRepository entryRepository,
            ReceiptFileRepository fileRepository) throws IOException {
        this.uploadDir = Path.of(uploadDir).toAbsolutePath().normalize();
        Files.createDirectories(this.uploadDir);
        this.entryRepository = entryRepository;
        this.fileRepository = fileRepository;
    }

    @Transactional
    public Map<String, Object> upload(String entryId, MultipartFile file, String category) throws IOException {
        DailyEntry entry = entryRepository.findActiveById(entryId)
                .orElseThrow(() -> new NotFoundException("Entry not found"));
        AuthUser user = AuthHelper.currentUser();
        if (entry.getStatus() == EntryStatus.LOCKED && !AuthHelper.isOperationsRole()) {
            throw new ForbiddenException("Entry is locked");
        }
        if (AuthHelper.isCashier() && !entry.getCashierId().equals(user.id())) {
            throw new ForbiddenException("Forbidden");
        }
        String original = file.getOriginalFilename();
        if (original == null || !original.matches("(?i).+\\.(jpg|jpeg|png|pdf|webp)$")) {
            throw new IllegalArgumentException("Only images and PDF allowed");
        }
        String stored = System.currentTimeMillis() + "-" + UUID.randomUUID() + getExtension(original);
        Files.copy(file.getInputStream(), uploadDir.resolve(stored));

        ReceiptFile rf = new ReceiptFile();
        rf.setEntry(entry);
        rf.setFilename(original);
        rf.setPath(stored);
        rf.setCategory(category);
        rf = fileRepository.save(rf);
        return Map.of(
                "id", rf.getId(),
                "entryId", rf.getEntryId(),
                "filename", rf.getFilename(),
                "path", rf.getPath(),
                "createdAt", rf.getCreatedAt().toString());
    }

    public List<Map<String, Object>> listForEntry(String entryId) {
        DailyEntry entry = entryRepository.findActiveByIdWithFiles(entryId)
                .orElseThrow(() -> new NotFoundException("Not found"));
        AuthUser user = AuthHelper.currentUser();
        if (AuthHelper.isCashier() && !entry.getCashierId().equals(user.id())) {
            throw new ForbiddenException("Forbidden");
        }
        return entry.getFiles().stream()
                .map(f -> Map.<String, Object>of(
                        "id", f.getId(),
                        "entryId", f.getEntryId(),
                        "filename", f.getFilename(),
                        "path", f.getPath(),
                        "createdAt", f.getCreatedAt().toString()))
                .collect(Collectors.toList());
    }

    public ReceiptFile resolveReceiptFile(String fileId) {
        ReceiptFile file = fileRepository.findById(fileId).orElseThrow(() -> new NotFoundException("Not found"));
        DailyEntry entry = entryRepository.findActiveById(file.getEntryId())
                .orElseThrow(() -> new NotFoundException("Not found"));
        AuthUser user = AuthHelper.currentUser();
        if (AuthHelper.isCashier() && !entry.getCashierId().equals(user.id())) {
            throw new ForbiddenException("Forbidden");
        }
        return file;
    }

    public Path resolveFile(String fileId) {
        return uploadDir.resolve(resolveReceiptFile(fileId).getPath());
    }

    private static String getExtension(String name) {
        int i = name.lastIndexOf('.');
        return i >= 0 ? name.substring(i) : "";
    }
}
