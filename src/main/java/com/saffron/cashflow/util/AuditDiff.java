package com.saffron.cashflow.util;

import com.saffron.cashflow.domain.AuditAction;
import java.util.*;

public final class AuditDiff {

    private static final Set<String> SENSITIVE = Set.of("password", "passwordHash", "token");

    private AuditDiff() {}

    public static List<Map<String, Object>> diff(Map<String, Object> before, Map<String, Object> after) {
        if (before == null && after == null) return List.of();
        Map<String, Object> b = before != null ? before : Map.of();
        Map<String, Object> a = after != null ? after : Map.of();
        Set<String> keys = new TreeSet<>();
        keys.addAll(b.keySet());
        keys.addAll(a.keySet());

        List<Map<String, Object>> changes = new ArrayList<>();
        for (String key : keys) {
            if (SENSITIVE.contains(key)) continue;
            Object oldVal = b.get(key);
            Object newVal = a.get(key);
            if (Objects.equals(normalize(oldVal), normalize(newVal))) continue;
            changes.add(Map.of(
                    "field", key,
                    "from", oldVal != null ? oldVal : "",
                    "to", newVal != null ? newVal : ""));
        }
        return changes;
    }

    public static String summarize(AuditAction action, String entityType, List<Map<String, Object>> changes) {
        String verb = switch (action) {
            case CREATE -> "Created";
            case UPDATE -> "Updated";
            case DELETE -> "Deleted";
            case SUBMIT -> "Submitted";
            case UNLOCK -> "Unlocked";
            case LOGIN -> "Signed in";
            case LOGIN_FAILED -> "Failed sign-in";
            case EXPORT -> "Exported";
            case SYNC -> "Synced";
            case STOCK_ADJUST -> "Adjusted stock";
            case STOCK_REVERT -> "Reverted stock change";
        };
        if (changes == null || changes.isEmpty()) {
            return verb + " " + entityType;
        }
        if (changes.size() == 1) {
            String field = String.valueOf(changes.get(0).get("field"));
            return verb + " " + entityType + ": " + field;
        }
        return verb + " " + entityType + " (" + changes.size() + " fields)";
    }

    private static Object normalize(Object value) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        return value;
    }
}
