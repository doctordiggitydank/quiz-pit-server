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
 * The web host panel's socket: /element/quizpit/ws/host. Any number of
 * panels may connect (multiple operator seats). Same message surface as the
 * stage: host commands, twitch_link, and every broadcast.
 */
@ServerEndpoint("/host")
public class HostEndpoint {

    private static final Logger logger = LoggerFactory.getLogger(HostEndpoint.class);

    private final ObjectMapper mapper = new ObjectMapper();

    private final Element element = ElementSupplier.getElementLocal(HostEndpoint.class).get();
    private final QuizGame game = element.getServiceLocator().getInstance(QuizGame.class);

    @OnOpen
    public void onOpen(final Session socket) {
        socket.setMaxIdleTimeout(10 * 60 * 1000L);
        game.registerHost(socket);
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
            logger.debug("Ignoring malformed host message: {}", text);
        }
    }

    @OnClose
    public void onClose(final Session socket) {
        game.unregisterHost(socket);
    }

    @OnError
    public void onError(final Session socket, final Throwable error) {
        logger.debug("Host socket error {}: {}", socket.getId(), error.getMessage());
    }

}
