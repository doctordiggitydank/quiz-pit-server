package com.quizpit.twitch;

import com.fasterxml.jackson.databind.JsonNode;
import com.quizpit.service.GuestStarService;
import com.quizpit.service.ProfileService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Quiz Pit's Twitch wiring. Configuration comes from element attributes:
 *
 * <ul>
 *   <li>twitch.deviceflow.client.id — the Quiz Pit Dev Console PUBLIC app
 *       (device flow, no secret). The broadcaster authorizes their own channel
 *       at runtime; the token arrives over the stage/host WebSocket as
 *       twitch_link. Guest Star + chat EventSub REQUIRE the broadcaster's own
 *       user token (no app-access-token path exists for Guest Star).</li>
 *   <li>twitch.chat.channel / twitch.broadcaster.id — optional static fallback
 *       identity for REST snapshots.</li>
 * </ul>
 *
 * Everything is optional: unconfigured, the gateway stays idle and the show
 * runs without chat/guests.
 */
public class TwitchGateway {

    private static final Logger logger = LoggerFactory.getLogger(TwitchGateway.class);

    /** A chat answer: message text + the viewer's Twitch identity. */
    public record ChatAnswer(String userId, String username, String login, String message) {}

    /** A channel.cheer event. */
    public record Cheer(String userId, String username, int bits, String message) {}

    private final HelixClient helix;
    private final GuestStarService guestStars;
    private final ProfileService profiles;

    private volatile String deviceflowClientId;
    private volatile String sessionChannel;      // broadcaster login, lowercase
    private volatile String sessionBroadcasterId;

    private TwitchEventSubClient sessionEventSub;

    private volatile Consumer<ChatAnswer> chatConsumer;
    private volatile Consumer<Cheer> cheerConsumer;

    /** Host-link state for the WS clients. */
    public record LinkStatus(boolean linked, String login, String clientId, String scopes) {}

    public TwitchGateway(final HelixClient helix, final GuestStarService guestStars,
                         final ProfileService profiles) {
        this.helix = helix;
        this.guestStars = guestStars;
        this.profiles = profiles;
    }

    public void start() {
        deviceflowClientId = attribute("twitch.deviceflow.client.id");
        helix.setClientId(deviceflowClientId);
        logger.info("Quiz Pit Twitch gateway up: deviceflow.client.id={}",
                deviceflowClientId == null ? "MISSING (twitch_link still works; EventSub won't)" : "set");
    }

    /** Reads an Element attribute, or null when absent. */
    public static String attribute(final String name) {
        try {
            final dev.getelements.elements.sdk.Element element =
                    dev.getelements.elements.sdk.ElementSupplier
                            .getElementLocal(TwitchGateway.class).get();
            return element.getElementRecord().attributes()
                    .getAttributeOptional(name).map(Object::toString).orElse(null);
        } catch (final Exception e) {
            return null;
        }
    }

    public LinkStatus linkStatus() {
        final boolean linked = sessionEventSub != null && sessionEventSub.isRunning();
        return new LinkStatus(linked, sessionChannel, deviceflowClientId,
                "user:read:chat channel:read:guest_star bits:read");
    }

    /** The required device-flow scopes for the client to display/poll. */
    public static final String DEVICE_FLOW_SCOPES =
            "user:read:chat channel:read:guest_star bits:read";

    /**
     * Links the broadcaster's own Twitch account (from the stage client's or
     * host panel's device-flow OAuth): opens EventSub for chat answers, cheers,
     * and the StreamTogether roster; snapshots the current Guest Star session;
     * enables profile-pic resolution.
     */
    public synchronized void linkHost(final String login, final String userId,
                                      final String accessToken, final String refreshToken) {
        if (login == null || login.isBlank() || userId == null || userId.isBlank()
                || accessToken == null || accessToken.isBlank()) {
            return;
        }
        if (deviceflowClientId == null || deviceflowClientId.isBlank()) {
            logger.warn("twitch_link ignored: twitch.deviceflow.client.id not configured");
            return;
        }
        unlinkHost();
        sessionChannel = login.trim().toLowerCase();
        sessionBroadcasterId = userId.trim();

        profiles.setBroadcasterToken(accessToken);
        profiles.setListener((id, pic) ->
                guestStars.applyProfilePics(Map.of(id, pic)));

        sessionEventSub = new TwitchEventSubClient(helix, deviceflowClientId,
                accessToken, refreshToken == null ? "" : refreshToken,
                sessionBroadcasterId, sessionBroadcasterId,
                TwitchEventSubClient.quizPitSubs(sessionBroadcasterId),
                this::onEvent);
        sessionEventSub.start();
        logger.info("Broadcaster Twitch linked: channel #{} (broadcaster {})",
                sessionChannel, sessionBroadcasterId);

        // The REST snapshot reconciles what the beta EventSub surface misses
        // (and captures a session already in progress at link time).
        guestStars.snapshot(accessToken, sessionBroadcasterId);
        profiles.resolve(guestStars.userIds());
    }

    /** Removes the session-scoped broadcaster link. */
    public synchronized void unlinkHost() {
        if (sessionEventSub != null) {
            try { sessionEventSub.stop(); } catch (final Exception e) {
                logger.debug("eventsub stop: {}", e.getMessage());
            }
            sessionEventSub = null;
        }
        if (sessionChannel != null) {
            logger.info("Broadcaster Twitch unlinked: channel #{}", sessionChannel);
        }
        sessionChannel = null;
        sessionBroadcasterId = null;
        profiles.setBroadcasterToken(null);
        guestStars.reset();
        profiles.reset();
    }

    /** Single-threaded EventSub dispatch (per client). */
    private void onEvent(final String type, final JsonNode payload) {
        switch (type) {
            case "channel.chat.message" -> {
                final JsonNode event = payload.path("event");
                final String text = event.path("message").path("text").asText();
                final Consumer<ChatAnswer> consumer = chatConsumer;
                if (consumer != null) {
                    consumer.accept(new ChatAnswer(
                            event.path("chatter_user_id").asText(""),
                            event.path("chatter_user_name").asText(""),
                            event.path("chatter_user_login").asText(""),
                            text));
                }
            }
            case "channel.cheer" -> {
                final JsonNode event = payload;
                final Consumer<Cheer> consumer = cheerConsumer;
                if (consumer != null) {
                    consumer.accept(new Cheer(
                            event.path("user_id").asText(""),
                            event.path("user_name").asText(""),
                            event.path("bits").asInt(0),
                            event.path("message").asText("")));
                }
            }
            case "channel.guest_star_guest.update" -> {
                guestStars.onGuestUpdate(payload);
                profiles.resolve(guestStars.userIds());
            }
            case "channel.guest_star_session.begin" -> guestStars.onSessionBegin(payload.path("event"));
            case "channel.guest_star_session.end" -> guestStars.onSessionEnd();
            default -> logger.debug("Unhandled EventSub type {}", type);
        }
    }

    public void setChatConsumer(final Consumer<ChatAnswer> consumer) {
        this.chatConsumer = consumer;
    }

    public void setCheerConsumer(final Consumer<Cheer> consumer) {
        this.cheerConsumer = consumer;
    }

    public GuestStarService guestStars() {
        return guestStars;
    }

    public ProfileService profiles() {
        return profiles;
    }

    public synchronized void stop() {
        unlinkHost();
        SharedWebSocketContainer.shutdown();
    }

}
