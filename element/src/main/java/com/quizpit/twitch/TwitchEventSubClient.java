package com.quizpit.twitch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.websocket.ClientEndpoint;
import jakarta.websocket.CloseReason;
import jakarta.websocket.DeploymentException;
import jakarta.websocket.OnClose;
import jakarta.websocket.OnError;
import jakarta.websocket.OnMessage;
import jakarta.websocket.OnOpen;
import jakarta.websocket.Session;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

/**
 * Twitch EventSub client over the WebSocket transport, generalized for The
 * Quiz Pit: subscribes to an arbitrary set of subscription types (chat
 * answers, cheers, and the StreamTogether/Guest Star roster events) after the
 * welcome frame, and dispatches every event to one consumer as
 * (type, payload). Handles user-token refresh (public device-flow client) and
 * keepalive/reconnect.
 *
 * Event delivery is single-threaded through the scheduler, so consumers can
 * mutate shared state without locking.
 */
public class TwitchEventSubClient {

    private static final Logger logger = LoggerFactory.getLogger(TwitchEventSubClient.class);

    /** One EventSub subscription: type, version, and its condition map. */
    public record SubSpec(String type, String version, Map<String, String> condition) {}

    /** Receives every notification: (subscription type, event payload). */
    public interface Listener extends BiConsumer<String, JsonNode> {}

    private static final String EVENTSUB_WS = "wss://eventsub.wss.twitch.tv/ws";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HelixClient helix;
    private final String clientId;
    private final String broadcasterId;
    private final String moderatorId;
    private final List<SubSpec> subs;
    private final Listener listener;

    private volatile String accessToken;
    private volatile String refreshToken;
    private volatile Session wsSession;
    private volatile boolean shouldRun;
    private volatile int attempts;
    private volatile String currentSessionId;

    /** Give up after this many consecutive failed attempts; a re-link
     *  (twitch_link over the stage socket) revives it. */
    private static final int MAX_ATTEMPTS = 20;

    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "quizpit-eventsub"));

    public TwitchEventSubClient(final HelixClient helix, final String clientId,
                                final String accessToken, final String refreshToken,
                                final String broadcasterId, final String moderatorId,
                                final List<SubSpec> subs, final Listener listener) {
        this.helix = helix;
        this.clientId = clientId;
        this.accessToken = accessToken;
        this.refreshToken = refreshToken;
        this.broadcasterId = broadcasterId;
        this.moderatorId = moderatorId;
        this.subs = List.copyOf(subs);
        this.listener = listener;
    }

    public void start() {
        shouldRun = true;
        connectWebSocket();
    }

    public boolean isRunning() {
        return shouldRun;
    }

    public void stop() {
        shouldRun = false;
        final Session s = wsSession;
        if (s != null) {
            try { s.close(); } catch (IOException ignored) { }
        }
        scheduler.shutdownNow();
    }

    // ------------------------------------------------------------------
    // WebSocket transport
    // ------------------------------------------------------------------

    private void connectWebSocket() {
        if (!shouldRun) return;
        scheduler.submit(() -> {
            try {
                SharedWebSocketContainer.get().connectToServer(
                        new EventSubEndpoint(), URI.create(EVENTSUB_WS));
            } catch (final DeploymentException | IOException e) {
                logger.warn("EventSub connect failed: {}", e.getMessage());
                scheduleReconnect();
            }
        });
    }

    private void scheduleReconnect() {
        if (!shouldRun) return;
        attempts++;
        if (attempts > MAX_ATTEMPTS) {
            logger.error("EventSub giving up after {} failed attempts", attempts - 1);
            shouldRun = false;
            return;
        }
        scheduler.schedule(this::connectWebSocket, Math.min(60, 2L * attempts), TimeUnit.SECONDS);
    }

    @ClientEndpoint
    public class EventSubEndpoint {

        @OnOpen
        public void onOpen(final Session session) {
            wsSession = session;
        }

        @OnMessage
        public void onMessage(final String text) {
            try {
                final JsonNode frame = MAPPER.readTree(text);
                final String type = frame.path("metadata").path("message_type").asText();
                switch (type) {
                    case "session_welcome" -> onWelcome(frame);
                    case "notification" -> onNotification(frame);
                    case "session_keepalive" -> { /* liveness proof */ }
                    case "session_reconnect" -> onReconnectRequested(frame);
                    case "revocation" -> logger.warn("EventSub subscription revoked: {}", frame);
                    default -> logger.debug("EventSub frame type {}: {}", type, text);
                }
            } catch (final Exception e) {
                logger.debug("EventSub frame error: {}", e.getMessage());
            }
        }

        @OnClose
        public void onClose(final Session session, final CloseReason reason) {
            logger.info("EventSub closed: {}", reason);
            scheduleReconnect();
        }

        @OnError
        public void onError(final Session session, final Throwable error) {
            logger.debug("EventSub socket error: {}", error.getMessage());
        }
    }

    /** Welcome frame: metadata.payload.session.id — subscribe here. */
    private void onWelcome(final JsonNode frame) {
        attempts = 0;
        currentSessionId = frame.path("payload").path("session").path("id").asText();
        logger.info("EventSub session welcome ({})", currentSessionId);
        scheduler.submit(() -> {
            for (final SubSpec spec : subs) {
                try {
                    helix.setClientId(clientId);
                    helix.createEventSubSubscription(accessToken, spec.type(), spec.version(),
                            spec.condition(), currentSessionId);
                } catch (final Exception e) {
                    // 409/403 usually means scope/token problems; a token refresh
                    // may fix 401. Log loudly — the show degrades without it.
                    logger.warn("EventSub subscribe {} v{} failed: {}", spec.type(), spec.version(), e.getMessage());
                    maybeRefreshToken(e);
                }
            }
        });
    }

    private void onNotification(final JsonNode frame) {
        final JsonNode payload = frame.path("payload").path("event");
        final String subType = frame.path("payload").path("subscription").path("type").asText();
        try {
            listener.accept(subType, payload);
        } catch (final Exception e) {
            logger.warn("EventSub dispatch {} failed: {}", subType, e.getMessage());
        }
    }

    private void onReconnectRequested(final JsonNode frame) {
        final String reconnectUrl = frame.path("payload").path("session").path("reconnect_url").asText(null);
        scheduler.submit(() -> {
            try {
                wsSession.close();
            } catch (IOException ignored) { }
            try {
                if (reconnectUrl != null && !reconnectUrl.isBlank()) {
                    SharedWebSocketContainer.get().connectToServer(new EventSubEndpoint(), URI.create(reconnectUrl));
                } else {
                    connectWebSocket();
                }
            } catch (final Exception e) {
                scheduleReconnect();
            }
        });
    }

    private void maybeRefreshToken(final Exception subscribeError) {
        final String message = subscribeError.getMessage() == null ? "" : subscribeError.getMessage();
        if (!message.contains("-> 401")) {
            return;
        }
        try {
            final HelixClient.Tokens fresh = helix.refreshUserToken(refreshToken);
            accessToken = fresh.accessToken();
            refreshToken = fresh.refreshToken();
            logger.info("EventSub user token refreshed after 401");
        } catch (final Exception refreshError) {
            logger.warn("Token refresh failed: {}", refreshError.getMessage());
        }
    }

    /** The subscription condition map for a broadcaster (+moderator) pair. */
    public static Map<String, String> broadcasterCondition(final String broadcasterId, final String moderatorId) {
        final Map<String, String> condition = new LinkedHashMap<>();
        condition.put("broadcaster_user_id", broadcasterId);
        if (moderatorId != null && !moderatorId.isBlank()) {
            condition.put("moderator_user_id", moderatorId);
        }
        return condition;
    }

    /** Convenience: cheer condition uses the older broadcaster_id key. */
    public static Map<String, String> cheerCondition(final String broadcasterId) {
        final Map<String, String> condition = new LinkedHashMap<>();
        condition.put("broadcaster_id", broadcasterId);
        return condition;
    }

    /** Convenience: the chat sub reads as the broadcaster's own user id. */
    public static Map<String, String> chatCondition(final String broadcasterId) {
        final Map<String, String> condition = new LinkedHashMap<>();
        condition.put("broadcaster_user_id", broadcasterId);
        condition.put("user_id", broadcasterId);
        return condition;
    }

    /** The full Quiz Pit subscription set: chat answers, cheers, Guest Star. */
    public static List<SubSpec> quizPitSubs(final String broadcasterId) {
        final List<SubSpec> specs = new ArrayList<>();
        specs.add(new SubSpec("channel.chat.message", "1", chatCondition(broadcasterId)));
        specs.add(new SubSpec("channel.cheer", "1", cheerCondition(broadcasterId)));
        specs.add(new SubSpec("channel.guest_star_session.begin", "beta",
                broadcasterCondition(broadcasterId, broadcasterId)));
        specs.add(new SubSpec("channel.guest_star_session.end", "beta",
                broadcasterCondition(broadcasterId, broadcasterId)));
        specs.add(new SubSpec("channel.guest_star_guest.update", "beta",
                broadcasterCondition(broadcasterId, broadcasterId)));
        return specs;
    }

}
