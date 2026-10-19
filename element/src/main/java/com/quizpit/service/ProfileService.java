package com.quizpit.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.quizpit.twitch.HelixClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * Resolves and caches Twitch profile pictures (GET /helix/users ->
 * profile_image_url) for any user id that flows through the show — StreamTogether
 * guests, chat answerers, cheerers. Fetched under the broadcaster's token,
 * cached for the element session (pics change rarely; a re-link resets).
 */
public class ProfileService {

    private static final Logger logger = LoggerFactory.getLogger(ProfileService.class);

    private final HelixClient helix;

    private final Map<String, String> picsByUser = new HashMap<>();
    private volatile String broadcasterToken;
    private volatile BiConsumer<String, String> listener;

    public ProfileService(final HelixClient helix) {
        this.helix = helix;
    }

    public void setBroadcasterToken(final String token) {
        this.broadcasterToken = token;
    }

    /** Notified per user as pics resolve: (userId, profileImageUrl). */
    public void setListener(final BiConsumer<String, String> listener) {
        this.listener = listener;
    }

    public String picFor(final String userId) {
        return userId == null ? null : picsByUser.get(userId);
    }

    /**
     * Fetches profile data for every id not already cached. Fire-and-forget:
     * failures only log (the caller re-fires on the next roster change).
     */
    public void resolve(final List<String> userIds) {
        final String token = broadcasterToken;
        if (token == null || userIds == null || userIds.isEmpty()) {
            return;
        }
        final List<String> missing = userIds.stream()
                .filter(id -> id != null && !picsByUser.containsKey(id))
                .distinct()
                .toList();
        if (missing.isEmpty()) {
            return;
        }
        try {
            for (final JsonNode user : helix.getUsers(token, missing)) {
                final String id = user.path("id").asText(null);
                final String pic = user.path("profile_image_url").asText(null);
                if (id != null && pic != null) {
                    picsByUser.put(id, pic);
                    final BiConsumer<String, String> consumer = listener;
                    if (consumer != null) {
                        consumer.accept(id, pic);
                    }
                }
            }
            logger.info("Profile pics resolved: {} of {} requested", picsByUser.size(), missing.size());
        } catch (final Exception e) {
            logger.warn("Profile pic fetch failed: {}", e.getMessage());
        }
    }

    public void reset() {
        picsByUser.clear();
    }

}
