package com.saffron.cashflow.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.saffron.cashflow.domain.NotificationDispatch;
import com.saffron.cashflow.domain.PushToken;
import com.saffron.cashflow.repository.PushTokenRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class PushNotificationService {

    private static final Logger log = LoggerFactory.getLogger(PushNotificationService.class);
    private static final URI EXPO_PUSH_URL = URI.create("https://exp.host/--/api/v2/push/send");

    private final PushTokenRepository pushTokenRepository;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public PushNotificationService(PushTokenRepository pushTokenRepository, ObjectMapper objectMapper) {
        this.pushTokenRepository = pushTokenRepository;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    public void sendToUser(String userId, String title, String body, Map<String, String> data) {
        List<PushToken> tokens = pushTokenRepository.findByUserId(userId);
        if (tokens.isEmpty()) {
            log.debug("No push tokens for user {}", userId);
            return;
        }
        for (PushToken token : tokens) {
            sendExpoPush(token.getExpoPushToken(), title, body, data);
        }
    }

    public void sendAfterDispatch(NotificationDispatch dispatch, Map<String, String> data) {
        Map<String, String> payload = data != null ? new HashMap<>(data) : new HashMap<>();
        payload.put("type", dispatch.getType().name());
        payload.put("referenceDate", dispatch.getReferenceDate().toString());
        sendToUser(dispatch.getUserId(), dispatch.getTitle(), dispatch.getBody(), payload);
    }

    private void sendExpoPush(String expoPushToken, String title, String body, Map<String, String> data) {
        try {
            Map<String, Object> message = new HashMap<>();
            message.put("to", expoPushToken);
            message.put("title", title);
            message.put("body", body);
            message.put("sound", "default");
            if (data != null && !data.isEmpty()) {
                message.put("data", data);
            }
            String json = objectMapper.writeValueAsString(message);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(EXPO_PUSH_URL)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .timeout(Duration.ofSeconds(15))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                log.warn("Expo push failed {}: {}", response.statusCode(), response.body());
                return;
            }
            JsonNode root = objectMapper.readTree(response.body());
            JsonNode status = root.path("data").path("status");
            if (status.isTextual() && "error".equals(status.asText())) {
                log.warn("Expo push error: {}", root.path("data").path("message").asText());
            }
        } catch (Exception ex) {
            log.warn("Failed to send Expo push: {}", ex.getMessage());
        }
    }
}
