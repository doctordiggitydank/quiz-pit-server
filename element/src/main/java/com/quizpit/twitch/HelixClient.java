package com.quizpit.twitch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal Helix REST client. Uses HttpURLConnection — the Element classloader
 * blocks java.net.http.HttpClient (a lesson carried over from Doodle or Die).
 * All calls take the broadcaster's user access token explicitly: nothing here
 * stores credentials.
 */
public class HelixClient {

    private static final Logger logger = LoggerFactory.getLogger(HelixClient.class);

    private static final String HELIX = "https://api.twitch.tv/helix";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private volatile String clientId;

    public void setClientId(final String clientId) {
        this.clientId = clientId;
    }

    private HttpURLConnection open(final String url, final String token) throws IOException {
        final HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestProperty("Client-Id", clientId == null ? "" : clientId);
        if (token != null && !token.isBlank()) {
            conn.setRequestProperty("Authorization", "Bearer " + token);
        }
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(15_000);
        return conn;
    }

    private JsonNode get(final String url, final String token) throws IOException {
        final HttpURLConnection conn = open(url, token);
        final int code = conn.getResponseCode();
        final InputStream body = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        final JsonNode json = readJson(body);
        if (code >= 400) {
            throw new IOException("Helix GET " + url + " -> " + code + " " + json);
        }
        return json;
    }

    /** Jackson can't infer a String|InputStream ternary — normalize here. */
    private static JsonNode readJson(final InputStream body) throws IOException {
        return MAPPER.readTree(body == null
                ? new java.io.ByteArrayInputStream("{}".getBytes(StandardCharsets.UTF_8)) : body);
    }

    /**
     * GET /helix/users — profile data (id, login, display_name,
     * profile_image_url) for up to 100 ids in one call.
     */
    public List<JsonNode> getUsers(final String token, final List<String> ids) throws IOException {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        final StringBuilder url = new StringBuilder(HELIX + "/users?");
        for (final String id : ids.subList(0, Math.min(100, ids.size()))) {
            url.append("id=").append(URLEncoder.encode(id, StandardCharsets.UTF_8)).append('&');
        }
        final JsonNode json = get(url.toString(), token);
        final List<JsonNode> users = new ArrayList<>();
        json.path("data").forEach(users::add);
        return users;
    }

    /**
     * GET /helix/guest_star/session — the ongoing StreamTogether/Guest Star
     * session for the broadcaster: data[0].{id, guests:[{user_id,
     * user_display_name, user_login, is_live, volume, slot_id?, assigned_at}]}.
     * 404 (no active session) is normal — returned as null, not thrown.
     */
    public JsonNode getGuestStarSession(final String token, final String broadcasterId,
                                        final String moderatorId) throws IOException {
        final HttpURLConnection conn = open(HELIX + "/guest_star/session"
                + "?broadcaster_id=" + URLEncoder.encode(broadcasterId, StandardCharsets.UTF_8)
                + "&moderator_id=" + URLEncoder.encode(moderatorId, StandardCharsets.UTF_8), token);
        final int code = conn.getResponseCode();
        final InputStream body = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        final JsonNode json = readJson(body);
        if (code == 404) {
            return null;
        }
        if (code >= 400) {
            throw new IOException("guest_star/session -> " + code + " " + json);
        }
        return json.path("data").path(0);
    }

    /**
     * POST /helix/eventsub/subscriptions — one EventSub subscription over the
     * WebSocket transport identified by session_id (the EventSub client's
     * welcome message id).
     */
    public void createEventSubSubscription(final String token, final String type, final String version,
                                           final Map<String, String> condition, final String sessionId)
            throws IOException {
        final Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", type);
        payload.put("version", version);
        payload.put("condition", condition);
        payload.put("transport", Map.of("method", "websocket", "session_id", sessionId));
        final byte[] body = MAPPER.writeValueAsBytes(payload);

        final HttpURLConnection conn = open(HELIX + "/eventsub/subscriptions", token);
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");
        try (OutputStream out = conn.getOutputStream()) {
            out.write(body);
        }
        final int code = conn.getResponseCode();
        final InputStream err = code >= 400 ? conn.getErrorStream() : null;
        if (code >= 400) {
            final JsonNode error = readJson(err);
            throw new IOException("eventsub subscribe " + type + " " + version + " -> " + code + " " + error);
        }
        logger.info("EventSub subscribed: {} v{} (session {})", type, version, sessionId);
    }

    /** Result of a user-token refresh. */
    public record Tokens(String accessToken, String refreshToken) {}

    /**
     * POST https://id.twitch.tv/oauth2/token — refresh a user token. The
     * Quiz Pit Dev Console app is a PUBLIC device-flow client: client_id only,
     * no secret.
     */
    public Tokens refreshUserToken(final String refreshToken) throws IOException {
        final String form = "grant_type=refresh_token"
                + "&refresh_token=" + URLEncoder.encode(refreshToken, StandardCharsets.UTF_8)
                + "&client_id=" + URLEncoder.encode(clientId == null ? "" : clientId, StandardCharsets.UTF_8);
        final HttpURLConnection conn = (HttpURLConnection)
                new URL("https://id.twitch.tv/oauth2/token").openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        try (OutputStream out = conn.getOutputStream()) {
            out.write(form.getBytes(StandardCharsets.UTF_8));
        }
        final int code = conn.getResponseCode();
        final InputStream body = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        final JsonNode json = readJson(body);
        if (code >= 400 || json.path("access_token").isMissingNode()) {
            throw new IOException("token refresh -> " + code + " " + json);
        }
        return new Tokens(json.path("access_token").asText(),
                json.path("refresh_token").asText(refreshToken));
    }

}
