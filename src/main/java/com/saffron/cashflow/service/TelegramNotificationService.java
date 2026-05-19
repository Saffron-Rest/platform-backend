package com.saffron.cashflow.service;

import com.saffron.cashflow.domain.AdminTelegramDispatch;
import com.saffron.cashflow.repository.AdminTelegramDispatchRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class TelegramNotificationService {

    private static final Logger log = LoggerFactory.getLogger(TelegramNotificationService.class);

    private final boolean enabled;
    private final String botToken;
    private final String chatId;
    private final AdminTelegramDispatchRepository dispatchRepository;
    private final RestClient telegramClient;

    public TelegramNotificationService(
            @Value("${app.telegram.enabled:false}") boolean enabled,
            @Value("${app.telegram.bot-token:}") String botToken,
            @Value("${app.telegram.chat-id:}") String chatId,
            AdminTelegramDispatchRepository dispatchRepository) {
        this.enabled = enabled;
        this.botToken = botToken == null ? "" : botToken.trim();
        this.chatId = chatId == null ? "" : chatId.trim();
        this.dispatchRepository = dispatchRepository;
        this.telegramClient = RestClient.builder().build();
    }

    public boolean isConfigured() {
        return enabled && !botToken.isBlank() && !chatId.isBlank();
    }

    public Map<String, Object> status() {
        return Map.of(
                "enabled", enabled,
                "configured", isConfigured(),
                "chatIdSet", !chatId.isBlank());
    }

    /** Sends immediately (no dedupe). Returns whether Telegram accepted the message. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean sendHtml(String html) {
        if (!isConfigured()) {
            return false;
        }
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("chat_id", chatId);
            body.put("text", html);
            body.put("parse_mode", "HTML");
            body.put("disable_web_page_preview", true);

            telegramClient
                    .post()
                    .uri("https://api.telegram.org/bot" + botToken + "/sendMessage")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (Exception ex) {
            log.warn("Telegram send failed: {}", ex.getMessage());
            return false;
        }
    }

    /**
     * Send once per dedupe key (e.g. {@code missing:2026-05-20}). Skips if key already sent.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean sendHtmlOnce(String dedupeKey, String html) {
        if (!isConfigured() || dedupeKey == null || dedupeKey.isBlank()) {
            return false;
        }
        if (dispatchRepository.existsByDedupeKey(dedupeKey)) {
            return false;
        }
        if (!sendHtml(html)) {
            return false;
        }
        AdminTelegramDispatch row = new AdminTelegramDispatch();
        row.setDedupeKey(dedupeKey);
        row.setPreview(html.length() > 500 ? html.substring(0, 497) + "…" : html);
        dispatchRepository.save(row);
        return true;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean sendTestMessage() {
        return sendHtml(
                "<b>Saffron — test</b>\n"
                        + "Admin Telegram alerts are connected.\n"
                        + "You will receive missing reports, cash shortages, and other alerts here.");
    }
}
