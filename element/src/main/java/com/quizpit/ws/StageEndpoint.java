package com.quizpit.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quizpit.game.QuizGame;
import dev.getelements.elements.sdk.Element;
import dev.getelements.elements.sdk.ElementSupplier;
import jakarta.websocket.OnClose;
import jakarta.websocket.OnError;
import jakarta.websocket.OnMessage;
import jakarta.websocket.OnOpen;
import jakarta.websocket.Session;
import jakarta.websocket.server.ServerEndpoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * The UE 5.8.3 broadcast client's socket: /element/quizpit/ws/stage.
 * ONE stage at a time — a new connect takes the slot (a dropped socket then a
 * re-host must revive cleanly). Receives host commands + the broadcaster's
 * twitch_link; receives everything the game broadcasts.
 */
@ServerEndpoint("/stage")
public class StageEndpoint {

    private static final Logger logger = LoggerFactory.getLogger(StageEndpoint.class);

    private final ObjectMapper mapper = new ObjectMapper();

    private final Element element = ElementSupplier.getElementLocal(StageEndpoint.class).get();
    private final QuizGame game = element.getServiceLocator().getInstance(QuizGame.class);

    @OnOpen
    public void onOpen(final Session socket) {
        // The lobby is quiet (timers only run during a question) — the host
        // container's default idle timeout (30s) would drop a quiet stage.
        // Clients also heartbeat {"type":"ping"} every 20s.
        socket.setMaxIdleTimeout(10 * 60 * 1000L);
        game.registerStage(socket);
    }

    @OnMessage
    public void onMessage(final Session socket, final String text) {
        try {
            final Object message = mapper.readValue(text, Map.class);
            if (message instanceof Map) {
                @SuppressWarnings("unchecked")
                final Map<String, Object> map = (Map<String, Object>) message;
                game.onClientMessage(socket, map);
            }
        } catch (final Exception e) {
            logger.debug("Ignoring malformed stage message: {}", text);
        }
    }

    @OnClose
    public void onClose(final Session socket) {
        game.unregisterStage(socket);
    }

    @OnError
    public void onError(final Session socket, final Throwable error) {
        logger.debug("Stage socket error {}: {}", socket.getId(), error.getMessage());
    }

}
