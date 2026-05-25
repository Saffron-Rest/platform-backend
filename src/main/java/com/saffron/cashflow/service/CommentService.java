package com.saffron.cashflow.service;

import com.saffron.cashflow.domain.*;
import com.saffron.cashflow.repository.CommentMentionRepository;
import com.saffron.cashflow.repository.CommentRepository;
import com.saffron.cashflow.repository.BankDepositRepository;
import com.saffron.cashflow.repository.CardSettlementRepository;
import com.saffron.cashflow.repository.DailyEntryRepository;
import com.saffron.cashflow.repository.ExpenseItemRepository;
import com.saffron.cashflow.repository.ManualDeliveryIncomeRepository;
import com.saffron.cashflow.repository.SalaryPaymentRepository;
import com.saffron.cashflow.repository.UserRepository;
import com.saffron.cashflow.security.AuthHelper;
import com.saffron.cashflow.security.ForbiddenException;
import com.saffron.cashflow.web.BadRequestException;
import com.saffron.cashflow.web.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Polymorphic comments on any taggable record. Plain-text body with
 * @username mentions parsed at write time; each mention triggers a push
 * notification via PushNotificationService.
 *
 * Comments are soft-deleted so the audit log remains stable. Authors can
 * edit/delete their own comments; admin/manager can edit/delete any.
 */
@Service
public class CommentService {

    /** Matches @handle in the body. Handles are 3-32 chars of
     *  letters/digits/dot/underscore/hyphen — enough to cover email-prefix
     *  style usernames the existing app uses. */
    private static final Pattern MENTION = Pattern.compile("@([A-Za-z0-9._-]{3,32})");
    private static final int MAX_BODY = 4000;

    private final CommentRepository commentRepository;
    private final CommentMentionRepository mentionRepository;
    private final UserRepository userRepository;
    private final DailyEntryRepository entryRepository;
    private final ExpenseItemRepository expenseRepository;
    private final SalaryPaymentRepository salaryPaymentRepository;
    private final ManualDeliveryIncomeRepository manualDeliveryRepository;
    private final BankDepositRepository bankDepositRepository;
    private final CardSettlementRepository cardSettlementRepository;
    private final PushNotificationService pushNotificationService;
    private final NotificationService notificationService;
    private final AuditService auditService;

    public CommentService(
            CommentRepository commentRepository,
            CommentMentionRepository mentionRepository,
            UserRepository userRepository,
            DailyEntryRepository entryRepository,
            ExpenseItemRepository expenseRepository,
            SalaryPaymentRepository salaryPaymentRepository,
            ManualDeliveryIncomeRepository manualDeliveryRepository,
            BankDepositRepository bankDepositRepository,
            CardSettlementRepository cardSettlementRepository,
            PushNotificationService pushNotificationService,
            NotificationService notificationService,
            AuditService auditService) {
        this.commentRepository = commentRepository;
        this.mentionRepository = mentionRepository;
        this.userRepository = userRepository;
        this.entryRepository = entryRepository;
        this.expenseRepository = expenseRepository;
        this.salaryPaymentRepository = salaryPaymentRepository;
        this.manualDeliveryRepository = manualDeliveryRepository;
        this.bankDepositRepository = bankDepositRepository;
        this.cardSettlementRepository = cardSettlementRepository;
        this.pushNotificationService = pushNotificationService;
        this.notificationService = notificationService;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(TaggedEntityType entityType, String entityId) {
        assertTargetExists(entityType, entityId);
        List<Comment> rows = commentRepository.findActiveByEntity(entityType, entityId);
        if (rows.isEmpty()) return List.of();
        Set<String> authorIds = rows.stream().map(Comment::getAuthorId).collect(Collectors.toSet());
        Map<String, User> authors = userRepository.findAllById(authorIds).stream()
                .collect(Collectors.toMap(User::getId, u -> u));
        return rows.stream().map(c -> toMap(c, authors)).toList();
    }

    /** Bulk count helper for list pages — keeps record cards lightweight. */
    @Transactional(readOnly = true)
    public Map<String, Long> countByEntities(TaggedEntityType entityType, List<String> entityIds) {
        if (entityIds == null || entityIds.isEmpty()) return Map.of();
        Map<String, Long> result = new HashMap<>();
        for (Object[] row : commentRepository.countActiveByEntities(entityType, entityIds)) {
            result.put((String) row[0], ((Number) row[1]).longValue());
        }
        return result;
    }

    @Transactional
    public Map<String, Object> create(TaggedEntityType entityType, String entityId, String body) {
        String clean = normalizeBody(body);
        assertTargetExists(entityType, entityId);
        Comment c = new Comment();
        c.setEntityType(entityType);
        c.setEntityId(entityId);
        c.setAuthorId(AuthHelper.currentUser().id());
        c.setBody(clean);
        c = commentRepository.save(c);
        List<User> mentioned = persistMentions(c, clean);
        notifyMentions(c, mentioned);
        auditService.logChange(AuthHelper.currentUser().id(), AuditAction.CREATE, "Comment", c.getId(),
                Map.of(), Map.of("body", clean, "on", entityType.name() + ":" + entityId), null);
        return toMap(c, Map.of(c.getAuthorId(), userRepository.findById(c.getAuthorId()).orElse(null)));
    }

    @Transactional
    public Map<String, Object> update(String id, String body) {
        Comment c = loadActive(id);
        assertCanModify(c);
        String clean = normalizeBody(body);
        String before = c.getBody();
        c.setBody(clean);
        c.setEditedAt(Instant.now());
        c = commentRepository.save(c);

        // Re-parse mentions: drop old ones, add new ones. Cheaper to do this
        // wholesale than diff — counts are tiny.
        mentionRepository.deleteByCommentId(c.getId());
        List<User> mentioned = persistMentions(c, clean);
        notifyMentions(c, mentioned);
        auditService.logChange(AuthHelper.currentUser().id(), AuditAction.UPDATE, "Comment", c.getId(),
                Map.of("body", before), Map.of("body", clean), null);
        return toMap(c, Map.of(c.getAuthorId(), userRepository.findById(c.getAuthorId()).orElse(null)));
    }

    @Transactional
    public void delete(String id) {
        Comment c = loadActive(id);
        assertCanModify(c);
        c.setDeletedAt(Instant.now());
        commentRepository.save(c);
        auditService.logChange(AuthHelper.currentUser().id(), AuditAction.DELETE, "Comment", c.getId(),
                Map.of("body", c.getBody()), Map.of(), null);
    }

    // ---------- helpers ----------

    private Comment loadActive(String id) {
        Comment c = commentRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Comment not found"));
        if (c.getDeletedAt() != null) throw new NotFoundException("Comment not found");
        return c;
    }

    /** Authors can edit/delete their own comments; operations roles can
     *  edit/delete anything. Comments never edited by anyone else. */
    private void assertCanModify(Comment c) {
        String me = AuthHelper.currentUser().id();
        if (c.getAuthorId().equals(me)) return;
        if (AuthHelper.isOperationsRole()) return;
        throw new ForbiddenException("Not allowed to edit this comment");
    }

    private String normalizeBody(String body) {
        if (body == null) throw new BadRequestException("Comment body required");
        String trimmed = body.strip();
        if (trimmed.isEmpty()) throw new BadRequestException("Comment body required");
        if (trimmed.length() > MAX_BODY) {
            throw new BadRequestException("Comment too long (max " + MAX_BODY + " chars)");
        }
        return trimmed;
    }

    private void assertTargetExists(TaggedEntityType entityType, String entityId) {
        boolean exists = switch (entityType) {
            case ENTRY -> entryRepository.findById(entityId).isPresent();
            case EXPENSE -> expenseRepository.findById(entityId).isPresent();
            case SALARY_PAYMENT -> salaryPaymentRepository.findById(entityId).isPresent();
            case MANUAL_DELIVERY -> manualDeliveryRepository.findById(entityId).isPresent();
            case BANK_DEPOSIT -> bankDepositRepository.findById(entityId).isPresent();
            case CARD_SETTLEMENT -> cardSettlementRepository.findById(entityId).isPresent();
        };
        if (!exists) throw new NotFoundException(entityType + " not found: " + entityId);
    }

    /** Scan the body for @handle tokens and resolve them against user
     *  records (case-insensitive on username OR email-prefix OR name). */
    private List<User> persistMentions(Comment c, String body) {
        Set<String> handles = new LinkedHashSet<>();
        Matcher m = MENTION.matcher(body);
        while (m.find()) handles.add(m.group(1).toLowerCase());
        if (handles.isEmpty()) return List.of();

        // Avoid N queries — load all candidate users once and match in memory.
        // The user table is tiny here, so the simple approach wins.
        List<User> all = userRepository.findAll();
        List<User> resolved = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String handle : handles) {
            for (User u : all) {
                if (matchesHandle(u, handle) && seen.add(u.getId())) {
                    resolved.add(u);
                    break;
                }
            }
        }

        // Don't notify the author about their own @ — that's annoying.
        resolved.removeIf(u -> u.getId().equals(c.getAuthorId()));

        for (User u : resolved) {
            CommentMention cm = new CommentMention();
            cm.setCommentId(c.getId());
            cm.setUserId(u.getId());
            mentionRepository.save(cm);
        }
        return resolved;
    }

    private static boolean matchesHandle(User u, String handle) {
        if (handle == null) return false;
        String hl = handle.toLowerCase();
        if (u.getUsername() != null && u.getUsername().equalsIgnoreCase(hl)) return true;
        if (u.getEmail() != null) {
            String prefix = u.getEmail().split("@")[0].toLowerCase();
            if (prefix.equals(hl)) return true;
        }
        if (u.getName() != null) {
            // Strip spaces & dots so "@john.doe" matches "John Doe".
            String compact = u.getName().toLowerCase().replaceAll("[ .]", "");
            if (compact.equals(hl)) return true;
        }
        return false;
    }

    private void notifyMentions(Comment c, List<User> mentioned) {
        if (mentioned.isEmpty()) return;
        String author = userRepository.findById(c.getAuthorId())
                .map(User::getName).orElse("Someone");
        String preview = c.getBody().length() > 120 ? c.getBody().substring(0, 117) + "…" : c.getBody();
        String url = urlFor(c.getEntityType(), c.getEntityId());
        Map<String, String> data = new LinkedHashMap<>();
        data.put("kind", "comment-mention");
        data.put("commentId", c.getId());
        data.put("entityType", c.getEntityType().name());
        data.put("entityId", c.getEntityId());
        for (User u : mentioned) {
            // In-app inbox row — primary delivery channel for web admins.
            try {
                notificationService.create(
                        u.getId(),
                        "mention",
                        author + " mentioned you",
                        preview,
                        url,
                        c.getEntityType().name(),
                        c.getEntityId(),
                        c.getAuthorId());
            } catch (Exception ignore) {
                // Persisting inbox is best-effort — push still goes through.
            }
            // Push channel for mobile clients. Failures are silent on
            // purpose so a missing token doesn't block a comment write.
            try {
                pushNotificationService.sendToUser(
                        u.getId(),
                        author + " mentioned you",
                        preview,
                        data);
            } catch (Exception ignore) {
            }
        }
    }

    private static String urlFor(com.saffron.cashflow.domain.TaggedEntityType type, String id) {
        return switch (type) {
            case ENTRY -> "/entry/" + id;
            case EXPENSE, MANUAL_DELIVERY -> "/finance";
            case SALARY_PAYMENT -> "/admin/payouts";
            case BANK_DEPOSIT -> "/admin/settings";
            case CARD_SETTLEMENT -> "/admin/settings";
        };
    }

    private Map<String, Object> toMap(Comment c, Map<String, User> authors) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.getId());
        m.put("entityType", c.getEntityType().name());
        m.put("entityId", c.getEntityId());
        m.put("body", c.getBody());
        m.put("authorId", c.getAuthorId());
        User a = authors.get(c.getAuthorId());
        m.put("authorName", a != null ? a.getName() : "Unknown");
        m.put("authorEmail", a != null ? a.getEmail() : null);
        m.put("createdAt", c.getCreatedAt().toString());
        m.put("editedAt", c.getEditedAt() != null ? c.getEditedAt().toString() : null);
        boolean mine = c.getAuthorId().equals(AuthHelper.currentUser().id());
        m.put("canEdit", mine || AuthHelper.isOperationsRole());
        return m;
    }
}
