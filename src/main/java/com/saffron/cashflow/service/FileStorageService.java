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

    /** Remove a file attached to a shift entry (e.g. POS report). Same auth rules as upload. */
    @Transactional
    public void deleteEntryFile(String fileId) throws IOException {
        ReceiptFile file = fileRepository.findById(fileId)
                .orElseThrow(() -> new NotFoundException("Not found"));
        String entryId = file.getEntryId();
        if (entryId == null) {
            throw new ForbiddenException("This endpoint only deletes shift-entry files");
        }
        DailyEntry entry = entryRepository.findActiveById(entryId)
                .orElseThrow(() -> new NotFoundException("Entry not found"));
        AuthUser user = AuthHelper.currentUser();
        if (entry.getStatus() == EntryStatus.LOCKED && !AuthHelper.isOperationsRole()) {
            throw new ForbiddenException("Entry is locked");
        }
        if (AuthHelper.isCashier() && !entry.getCashierId().equals(user.id())) {
            throw new ForbiddenException("Forbidden");
        }
        try {
            Files.deleteIfExists(uploadDir.resolve(file.getPath()));
        } catch (IOException ignored) {
            // file might already be gone; keep DB cleanup going either way
        }
        fileRepository.delete(file);
    }

    public ReceiptFile resolveReceiptFile(String fileId) {
        ReceiptFile file = fileRepository.findById(fileId).orElseThrow(() -> new NotFoundException("Not found"));
        if (file.getOwnerExpenseId() != null) {
            // Owner-expense receipts: anyone who can view owner
            // expenses can also see their proof. Same gate the
            // OwnerExpenseService uses on the read path.
            AuthHelper.requireAdminOr(
                    com.saffron.cashflow.domain.Permission.OWNER_EXPENSES_VIEW,
                    com.saffron.cashflow.domain.Permission.OWNER_EXPENSES_MANAGE,
                    com.saffron.cashflow.domain.Permission.OWNER_EXPENSES_FILE);
            return file;
        }
        String entryId = file.getEntryId();
        if (entryId == null) {
            // Standalone (post-close) expense invoice — not tied to any shift.
            // Only operations roles can create them, so the same restriction applies on read.
            AuthHelper.requireOperations();
            return file;
        }
        DailyEntry entry = entryRepository.findActiveById(entryId)
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

    // ---------- Menu item photos ----------

    /**
     * Save a menu item photo into {@code <uploadDir>/menu/} and return the
     * relative path (e.g. {@code menu/1716628231-abc.jpg}). Independent of the
     * shift-entry receipt flow because menu photos belong to the catalog, not
     * a daily entry.
     */
    public String storeMenuImage(MultipartFile file) throws IOException {
        AuthHelper.requireOperations();
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Image file is empty — pick a photo and try again");
        }
        String original = file.getOriginalFilename();
        if (original == null || original.isBlank()) original = "photo.jpg";
        // Accept the formats we know OpenPDF can render. HEIC arrives from
        // iPhones — we reject it explicitly with a clear hint so the user
        // knows to share as JPG instead of getting a silent 4xx.
        String lower = original.toLowerCase();
        if (lower.endsWith(".heic") || lower.endsWith(".heif")) {
            throw new IllegalArgumentException(
                    "HEIC photos are not supported. On iPhone, set Camera → Formats → Most Compatible, "
                            + "or share the photo as JPG before uploading.");
        }
        if (!lower.matches(".+\\.(jpg|jpeg|png|webp)$")) {
            throw new IllegalArgumentException("Only JPG, PNG or WEBP images are allowed");
        }
        Path menuDir = uploadDir.resolve("menu");
        Files.createDirectories(menuDir);
        String stored = "menu/" + System.currentTimeMillis() + "-" + UUID.randomUUID()
                + getExtension(original).toLowerCase();
        Files.copy(file.getInputStream(), uploadDir.resolve(stored));
        return stored;
    }

    /**
     * Generic uploader used by the operations features (incidents, HACCP,
     * checklists). Files are stored under {@code <uploadDir>/<prefix>/}.
     * Returns the relative path so callers can persist it in their own
     * entities.
     */
    public String storeUnderPrefix(MultipartFile file, String prefix) throws IOException {
        AuthHelper.requireOperations();
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("File is empty");
        }
        if (prefix == null || prefix.isBlank() || prefix.contains("..") || prefix.contains("/")) {
            throw new IllegalArgumentException("Invalid prefix");
        }
        String original = file.getOriginalFilename();
        if (original == null || original.isBlank()) original = "file";
        String lower = original.toLowerCase();
        if (lower.endsWith(".heic") || lower.endsWith(".heif")) {
            throw new IllegalArgumentException(
                    "HEIC photos are not supported. On iPhone, set Camera → Formats → Most Compatible, "
                            + "or share the photo as JPG before uploading.");
        }
        if (!lower.matches(".+\\.(jpg|jpeg|png|webp|pdf)$")) {
            throw new IllegalArgumentException("Only JPG, PNG, WEBP or PDF allowed");
        }
        Path dir = uploadDir.resolve(prefix);
        Files.createDirectories(dir);
        String stored = prefix + "/" + System.currentTimeMillis() + "-" + UUID.randomUUID()
                + getExtension(original).toLowerCase();
        Files.copy(file.getInputStream(), uploadDir.resolve(stored));
        return stored;
    }

    /** Resolve a file under any operations prefix for download/serving. */
    public Path resolveOperationsFile(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) return null;
        if (relativePath.contains("..") || relativePath.startsWith("/")) return null;
        return uploadDir.resolve(relativePath).normalize();
    }

    public Path resolveMenuImage(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) return null;
        // Defence against path traversal.
        if (relativePath.contains("..") || relativePath.startsWith("/")) return null;
        return uploadDir.resolve(relativePath).normalize();
    }

    /** Expose the configured upload root for low-level consumers (PDF builder). */
    public Path getUploadDir() {
        return uploadDir;
    }
}
