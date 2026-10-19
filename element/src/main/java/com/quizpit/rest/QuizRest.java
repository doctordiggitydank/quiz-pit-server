package com.quizpit.rest;

import com.quizpit.game.QuizGame;
import com.quizpit.service.GuestStarService;
import com.quizpit.twitch.TwitchGateway;
import dev.getelements.elements.sdk.Element;
import dev.getelements.elements.sdk.ElementSupplier;
import jakarta.inject.Named;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Read-only show status for the host panel's initial load and for monitoring.
 * Served at /element/quizpit/rest/status — no auth (public show state, same
 * posture as Doodle or Die's room status).
 */
@Path("/status")
public class QuizRest {

    private final Element element = ElementSupplier.getElementLocal(QuizRest.class).get();
    private final QuizGame game = element.getServiceLocator().getInstance(QuizGame.class);
    private final TwitchGateway gateway = element.getServiceLocator().getInstance(TwitchGateway.class);
    private final GuestStarService guestStars = element.getServiceLocator()
            .getInstance(GuestStarService.class);

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response status() {
        final Map<String, Object> status = new LinkedHashMap<>();
        final TwitchGateway.LinkStatus link = gateway.linkStatus();
        status.put("phase", game.phase());
        status.put("players", game.playerCount());
        status.put("twitch", Map.of(
                "linked", link.linked(),
                "login", link.login() == null ? "" : link.login(),
                "clientId", link.clientId() == null ? "" : link.clientId(),
                "scopes", link.scopes()));
        status.put("guests", GuestStarService.toMaps(guestStars.roster()));
        return Response.ok(status).build();
    }

}
