package com.quizpit.twitch;

import jakarta.websocket.ContainerProvider;
import jakarta.websocket.WebSocketContainer;

/**
 * ONE WebSocket client container for the whole gateway. Jetty's
 * {@link ContainerProvider#getWebSocketContainer()} mints a brand-new container
 * — with its own 8+ thread pool — on every call, and nothing ever stops them:
 * reconnect churn (normal and frequent with Twitch) leaked ~500 containers /
 * ~4,000 threads in 18h and exhausted a pod's PID limit (the 2026-09-25
 * Doodle or Die prod crash — same failure mode lives here if "fixed").
 * Cache the container and reuse it across every connect and reconnect.
 */
public final class SharedWebSocketContainer {

    private static volatile WebSocketContainer container;

    private SharedWebSocketContainer() {}

    public static WebSocketContainer get() {
        WebSocketContainer c = container;
        if (c == null) {
            synchronized (SharedWebSocketContainer.class) {
                c = container;
                if (c == null) {
                    c = ContainerProvider.getWebSocketContainer();
                    // Twitch IRC/EventSub pings every ~5 minutes; Jetty's default
                    // 30s idle timeout kills sessions long before that.
                    c.setDefaultMaxSessionIdleTimeout(10 * 60 * 1000L);
                    container = c;
                }
            }
        }
        return c;
    }

    /** Best-effort shutdown on element teardown (Jetty-specific stop/close). */
    public static void shutdown() {
        final WebSocketContainer c = container;
        container = null;
        if (c == null) {
            return;
        }
        try {
            c.getClass().getMethod("stop").invoke(c);
        } catch (final Exception stopFailed) {
            try {
                c.getClass().getMethod("close").invoke(c);
            } catch (final Exception ignored) {
                // provider exposes neither; threads die with the classloader
            }
        }
    }

}
