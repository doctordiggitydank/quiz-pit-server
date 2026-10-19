package com.quizpit.service;

import dev.getelements.elements.sdk.annotation.ElementServiceExport;
import com.fasterxml.jackson.databind.JsonNode;
import com.quizpit.twitch.HelixClient;
import com.google.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Tracks who is connected to the broadcaster's StreamTogether (Guest Star)
 * call — the roster the UE show renders on the in-level GuestBoard.
 *
 * Two sources reconcile into one roster: the EventSub Guest Star events
 * (live updates, version "beta") and a REST snapshot taken when the host
 * links (the beta APIs are confirmed to lag the product, so the snapshot is
 * also the recovery path after a missed event).
 */
@ElementServiceExport
public class GuestStarService {

    private static final Logger logger = LoggerFactory.getLogger(GuestStarService.class);

    /** One guest in the StreamTogether call. */
    public record GuestInfo(String userId, String displayName, String login,
                            boolean isLive, String state, String slotId, String picUrl) {}

    private final HelixClient helix;

    private final Map<String, GuestInfo> guestsByUser = new LinkedHashMap<>();
    private volatile String sessionId;

    private volatile Consumer<List<GuestInfo>> rosterListener;

    @Inject
    public GuestStarService(final HelixClient helix) {
        this.helix = helix;
    }

    public void setRosterListener(final Consumer<List<GuestInfo>> listener) {
        this.rosterListener = listener;
    }

    /** Current roster, in slot/stable order. */
    public List<GuestInfo> roster() {
        synchronized (guestsByUser) {
            return new ArrayList<>(guestsByUser.values());
        }
    }

    public String sessionId() {
        return sessionId;
    }

    /** EventSub channel.guest_star_guest.update (beta). */
    public void onGuestUpdate(final JsonNode payload) {
        final String guestId = payload.path("guest_user_id").asText(null);
        final String state = payload.path("state").asText("");
        synchronized (guestsByUser) {
            if (guestId == null || guestId.isBlank() || "removed".equals(state)) {
                if (guestId != null && !guestId.isBlank()) {
                    guestsByUser.remove(guestId);
                }
            } else {
                final GuestInfo updated = new GuestInfo(
                        guestId,
                        payload.path("guest_user_name").asText(""),
                        payload.path("guest_user_login").asText(""),
                        "live".equals(state),
                        state,
                        payload.path("slot_id").asText(null),
                        guestsByUser.containsKey(guestId)
                                ? guestsByUser.get(guestId).picUrl() : null);
                guestsByUser.put(guestId, updated);
            }
        }
        logger.info("Guest Star update: user={} state={} (roster {})", guestId, state, size());
        emit();
    }

    /** EventSub channel.guest_star_session.begin (beta). */
    public void onSessionBegin(final JsonNode payload) {
        sessionId = payload.path("session_id").asText(null);
        logger.info("Guest Star session began: {}", sessionId);
        emit();
    }

    /** EventSub channel.guest_star_session.end (beta). */
    public void onSessionEnd() {
        sessionId = null;
        synchronized (guestsByUser) {
            guestsByUser.clear();
        }
        logger.info("Guest Star session ended");
        emit();
    }

    /**
     * REST snapshot — taken on host link and safe to call as a recovery path.
     * Reconciles the roster against the live session; a 404/no-session just
     * leaves the roster untouched.
     */
    public void snapshot(final String token, final String broadcasterId) {
        try {
            final JsonNode session = helix.getGuestStarSession(token, broadcasterId, broadcasterId);
            if (session == null || session.isMissingNode()) {
                logger.info("No active Guest Star session on snapshot");
                return;
            }
            sessionId = session.path("id").asText(null);
            final Map<String, GuestInfo> fresh = new LinkedHashMap<>();
            for (final JsonNode guest : session.path("guests")) {
                final String userId = guest.path("user_id").asText(null);
                if (userId == null) {
                    continue;
                }
                // Pic urls ride along later via ProfileService.
                fresh.put(userId, new GuestInfo(
                        userId,
                        guest.path("user_display_name").asText(""),
                        guest.path("user_login").asText(""),
                        guest.path("is_live").asBoolean(false),
                        guest.path("is_live").asBoolean(false) ? "live" : "ready",
                        guest.path("slot_id").asText(null),
                        guestsByUser.containsKey(userId)
                                ? guestsByUser.get(userId).picUrl() : null));
            }
            synchronized (guestsByUser) {
                guestsByUser.clear();
                guestsByUser.putAll(fresh);
            }
            logger.info("Guest Star snapshot: {} guest(s), session {}", fresh.size(), sessionId);
            emit();
        } catch (final Exception e) {
            logger.warn("Guest Star snapshot failed: {}", e.getMessage());
        }
    }

    /** Attaches resolved profile-pic urls to the roster and re-emits. */
    public void applyProfilePics(final Map<String, String> pics) {
        if (pics.isEmpty()) {
            return;
        }
        synchronized (guestsByUser) {
            for (final Map.Entry<String, GuestInfo> entry : guestsByUser.entrySet()) {
                final String pic = pics.get(entry.getKey());
                if (pic != null && !pic.isBlank()) {
                    final GuestInfo g = entry.getValue();
                    entry.setValue(new GuestInfo(g.userId(), g.displayName(), g.login(),
                            g.isLive(), g.state(), g.slotId(), pic));
                }
            }
        }
        emit();
    }

    /** The user ids currently in the roster. */
    public List<String> userIds() {
        synchronized (guestsByUser) {
            return new ArrayList<>(guestsByUser.keySet());
        }
    }

    public int size() {
        synchronized (guestsByUser) {
            return guestsByUser.size();
        }
    }

    private void emit() {
        final Consumer<List<GuestInfo>> listener = rosterListener;
        if (listener != null) {
            listener.accept(roster());
        }
    }

    /** Clears everything (host unlink). */
    public void reset() {
        sessionId = null;
        synchronized (guestsByUser) {
            guestsByUser.clear();
        }
        emit();
    }

    /** Stub-safe: a listener may want the roster as one JSON-friendly list. */
    public static List<Map<String, Object>> toMaps(final Collection<GuestInfo> roster) {
        final List<Map<String, Object>> maps = new ArrayList<>();
        for (final GuestInfo g : roster) {
            maps.add(Map.of(
                    "userId", g.userId() == null ? "" : g.userId(),
                    "displayName", g.displayName() == null ? "" : g.displayName(),
                    "login", g.login() == null ? "" : g.login(),
                    "isLive", g.isLive(),
                    "state", g.state() == null ? "" : g.state(),
                    "slotId", g.slotId() == null ? "" : g.slotId(),
                    "picUrl", g.picUrl() == null ? "" : g.picUrl()));
        }
        return maps;
    }

}
