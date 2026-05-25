package com.saffron.cashflow.service;

import com.saffron.cashflow.domain.Notification;
import com.saffron.cashflow.domain.User;
import com.saffron.cashflow.repository.NotificationRepository;
import com.saffron.cashflow.repository.UserRepository;
import com.saffron.cashflow.security.AuthHelper;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.*;

/**
 * Writes persistent in-app notifications and surfaces them to the
 * currently-authenticated user. Push notifications still fan out via
 * {@link PushNotificationService}; this layer adds the catch-up inbox
 * for users sitting at a desktop where push doesn't apply.
 *
 * Helpers are intentionally narrow — callers pass the user id, title,
 * body, and optional url; we never silently include extra metadata.
 */
@Service
public class NotificationService {

    /** Cap on the inbox payload. Older items still live in the DB and can
     *  be paginated later; the UI only ever shows the last batch. */
    private static final int DEFAULT_LIMIT = 30;

    private final NotificationRepository repository;
    private final UserRepository userRepository;

    public NotificationService(NotificationRepository repository, UserRepository userRepository) {
        this.repository = repository;
        this.userRepository = userRepository;
    }

    /** Create and persist a notification. Returns the saved row so callers
     *  can correlate (eg. tests). */
    @Transactional
    public Notification create(String userId, String kind, String title, String body,
                               String url, String entityType, String entityId, String actorUserId) {
        Notification n = new Notification();
        n.setUserId(userId);
        n.setKind(kind);
        n.setTitle(title);
        n.setBody(body);
        n.setUrl(url);
        n.setEntityType(entityType);
        n.setEntityId(entityId);
        n.setActorUserId(actorUserId);
        return repository.save(n);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> inbox() {
        String userId = AuthHelper.currentUser().id();
        List<Notification> rows = repository.findByUserIdOrderByCreatedAtDesc(
                userId, PageRequest.of(0, DEFAULT_LIMIT));
        long unread = repository.countByUserIdAndReadAtIsNull(userId);

        // Resolve actor names in a single query so the UI can show "Alex
        // mentioned you" without N follow-ups.
        Set<String> actorIds = new HashSet<>();
        for (Notification n : rows) if (n.getActorUserId() != null) actorIds.add(n.getActorUserId());
        Map<String, String> actorNames = new HashMap<>();
        for (User u : userRepository.findAllById(actorIds)) actorNames.put(u.getId(), u.getName());

        List<Map<String, Object>> items = new ArrayList<>(rows.size());
        for (Notification n : rows) items.add(toMap(n, actorNames));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        out.put("unread", unread);
        return out;
    }

    /** Just the unread counter — cheap enough to poll every minute. */
    @Transactional(readOnly = true)
    public long unreadCount() {
        return repository.countByUserIdAndReadAtIsNull(AuthHelper.currentUser().id());
    }

    @Transactional
    public void markRead(String id) {
        Notification n = repository.findById(id).orElse(null);
        if (n == null) return;
        // Defensive: only the owner can mark their own notifications.
        if (!n.getUserId().equals(AuthHelper.currentUser().id())) return;
        if (n.getReadAt() == null) {
            n.setReadAt(Instant.now());
            repository.save(n);
        }
    }

    @Transactional
    public int markAllRead() {
        return repository.markAllRead(AuthHelper.currentUser().id(), Instant.now());
    }

    private static Map<String, Object> toMap(Notification n, Map<String, String> actors) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", n.getId());
        m.put("kind", n.getKind());
        m.put("title", n.getTitle());
        m.put("body", n.getBody());
        m.put("url", n.getUrl());
        m.put("entityType", n.getEntityType());
        m.put("entityId", n.getEntityId());
        m.put("actorUserId", n.getActorUserId());
        m.put("actorName", n.getActorUserId() == null ? null : actors.get(n.getActorUserId()));
        m.put("createdAt", n.getCreatedAt().toString());
        m.put("readAt", n.getReadAt() != null ? n.getReadAt().toString() : null);
        return m;
    }
}
