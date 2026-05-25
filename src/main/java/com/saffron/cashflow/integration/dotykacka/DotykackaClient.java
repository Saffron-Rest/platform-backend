package com.saffron.cashflow.integration.dotykacka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.saffron.cashflow.web.BadRequestException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Thin HTTP client for the Dotykačka API v2.
 *
 * Auth model:
 *  1. The integration owner obtains a long-lived <em>refresh token</em> via
 *     the browser-based connector flow (admin.dotykacka.cz/client/connect).
 *  2. We exchange the refresh token + cloudId for a short-lived <em>access
 *     token</em> (~1h) at /v2/signin/token.
 *  3. We cache the access token per cloudId in memory and re-issue when it's
 *     within 60 seconds of expiry.
 *
 * Only the endpoints we actually use are wrapped — everything else falls
 * through to {@link #get(String, String, String)}.
 */
@Component
public class DotykackaClient {

    private static final Logger LOG = LoggerFactory.getLogger(DotykackaClient.class);

    /** Production API root. Dotykačka also offers test.api.dotykacka.cz —
     *  swap via the {@code base} parameter if you ever need to test. */
    public static final String DEFAULT_BASE = "https://api.dotykacka.cz";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration ACCESS_TOKEN_LEEWAY = Duration.ofSeconds(60);

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /** Cached access tokens keyed by cloudId. */
    private final Map<String, CachedToken> tokenCache = new HashMap<>();

    /**
     * Obtain (or refresh) an access token for a specific cloud.
     *
     * @param refreshToken long-lived refresh token from the connector flow
     * @param cloudId      Dotykačka cloud ID
     * @return non-null access token
     */
    public synchronized String getAccessToken(String refreshToken, String cloudId) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new BadRequestException("Dotykačka refresh token is not set");
        }
        if (cloudId == null || cloudId.isBlank()) {
            throw new BadRequestException("Dotykačka cloud ID is not set");
        }
        String cacheKey = cloudId + "::" + refreshToken.hashCode();
        CachedToken cached = tokenCache.get(cacheKey);
        if (cached != null && Instant.now().isBefore(cached.expiresAt.minus(ACCESS_TOKEN_LEEWAY))) {
            return cached.token;
        }
        String body = "{\"_cloudId\":\"" + cloudId.replace("\"", "\\\"") + "\"}";
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(DEFAULT_BASE + "/v2/signin/token"))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "User " + refreshToken)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() / 100 != 2) {
                LOG.warn("Dotykačka signin failed: status={} body={}", res.statusCode(), res.body());
                throw new BadRequestException("Dotykačka rejected sign-in (" + res.statusCode() + ")");
            }
            JsonNode json = MAPPER.readTree(res.body());
            String token = json.path("accessToken").asText(null);
            if (token == null) throw new BadRequestException("Dotykačka response missing accessToken");
            // Dotykačka does not always return an explicit expiry — default to 50 minutes.
            long expiresInSec = json.path("expiresIn").asLong(50 * 60);
            CachedToken fresh = new CachedToken(token, Instant.now().plusSeconds(expiresInSec));
            tokenCache.put(cacheKey, fresh);
            return token;
        } catch (BadRequestException e) {
            throw e;
        } catch (Exception e) {
            throw new BadRequestException("Could not reach Dotykačka: " + e.getMessage());
        }
    }

    /**
     * Issue a GET to {@code path} with the given access token. Returns the
     * parsed JSON body. Throws on any non-2xx response.
     */
    public JsonNode get(String accessToken, String path, String queryString) {
        String url = DEFAULT_BASE + path + (queryString == null || queryString.isBlank() ? "" : "?" + queryString);
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + accessToken)
                .header("Accept", "application/json")
                .GET()
                .build();
        try {
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() / 100 != 2) {
                String body = res.body() == null ? "" : res.body();
                LOG.warn("Dotykačka GET {} failed: status={} url={} body={}", path, res.statusCode(), url,
                        body.length() > 400 ? body.substring(0, 400) : body);
                // Surface the Dotykačka error message back to the admin UI so
                // they don't see a generic "Dotykačka error 400" — most 4xx
                // responses carry a {"message":"..."} or {"errors":[...]} body
                // that explains exactly which filter / operator was rejected.
                String detail = extractErrorMessage(body);
                throw new BadRequestException(
                        "Dotykačka error " + res.statusCode()
                                + (detail.isEmpty() ? "" : " — " + detail));
            }
            return MAPPER.readTree(res.body());
        } catch (BadRequestException e) {
            throw e;
        } catch (Exception e) {
            throw new BadRequestException("Dotykačka request failed: " + e.getMessage());
        }
    }

    /** Best-effort extraction of the human-readable error from a Dotykačka
     *  failure body. Handles {@code {"message":"..."}}, {@code {"errors":[...]}}
     *  and falls back to a snippet of the raw body. */
    private static String extractErrorMessage(String body) {
        if (body == null || body.isBlank()) return "";
        try {
            JsonNode json = MAPPER.readTree(body);
            if (json.hasNonNull("message")) return json.get("message").asText();
            if (json.hasNonNull("error")) return json.get("error").asText();
            if (json.has("errors") && json.get("errors").isArray() && json.get("errors").size() > 0) {
                JsonNode first = json.get("errors").get(0);
                if (first.isTextual()) return first.asText();
                if (first.hasNonNull("message")) return first.get("message").asText();
            }
        } catch (Exception ignored) {
            // Not JSON or unexpected shape — fall through to raw snippet.
        }
        String snippet = body.replaceAll("\\s+", " ").trim();
        return snippet.length() > 200 ? snippet.substring(0, 200) + "…" : snippet;
    }

    /**
     * Lightweight URL-encoder for query parameters.
     */
    public static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    // ---------- Webhook registration ----------

    /**
     * Register a webhook on the Dotypos side so they push order changes to us
     * in real time. Returns the Dotypos webhook id we can use for deletion.
     *
     * @param payloadEntity see Dotypos docs — for sales pass {@code "ORDERBEAN"}.
     */
    public long registerWebhook(String accessToken, String cloudId, String url, String payloadEntity) {
        String body = "{\"method\":\"POST\","
                + "\"url\":\"" + url.replace("\"", "\\\"") + "\","
                + "\"payloadEntity\":\"" + payloadEntity + "\","
                + "\"payloadVersion\":\"V1\"}";
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(DEFAULT_BASE + "/v2/clouds/" + cloudId + "/webhooks"))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + accessToken)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() / 100 != 2) {
                LOG.warn("Dotypos webhook register failed: status={} body={}", res.statusCode(), res.body());
                throw new BadRequestException("Dotypos rejected webhook register (" + res.statusCode() + ")");
            }
            JsonNode json = MAPPER.readTree(res.body());
            // Response can be either the created object or an array — handle both.
            JsonNode target = json.isArray() && json.size() > 0 ? json.get(0) : json;
            long id = target.path("id").asLong(0);
            if (id == 0) throw new BadRequestException("Dotypos response did not include webhook id");
            return id;
        } catch (BadRequestException e) {
            throw e;
        } catch (Exception e) {
            throw new BadRequestException("Dotypos webhook register error: " + e.getMessage());
        }
    }

    /** Delete a previously registered webhook. Best-effort — already-deleted
     *  webhooks should be tolerated by the caller. */
    public void deleteWebhook(String accessToken, String cloudId, long webhookId) {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(DEFAULT_BASE + "/v2/clouds/" + cloudId + "/webhooks/" + webhookId))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + accessToken)
                .DELETE()
                .build();
        try {
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() == 404) return;
            if (res.statusCode() / 100 != 2) {
                throw new BadRequestException("Dotypos webhook delete failed: " + res.statusCode());
            }
        } catch (BadRequestException e) {
            throw e;
        } catch (Exception e) {
            throw new BadRequestException("Dotypos webhook delete error: " + e.getMessage());
        }
    }

    private record CachedToken(String token, Instant expiresAt) {}
}
