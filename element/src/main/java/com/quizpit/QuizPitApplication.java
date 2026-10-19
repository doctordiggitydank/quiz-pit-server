package com.quizpit;

import com.quizpit.rest.QuizRest;
import dev.getelements.elements.sdk.annotation.ElementDefaultAttribute;
import dev.getelements.elements.sdk.annotation.ElementServiceExport;
import dev.getelements.elements.sdk.annotation.ElementServiceImplementation;
import io.swagger.v3.jaxrs2.integration.resources.OpenApiResource;
import jakarta.ws.rs.core.Application;

import java.util.Set;

@ElementServiceImplementation
@ElementServiceExport(Application.class)
public class QuizPitApplication extends Application {

    @ElementDefaultAttribute("true")
    public static final String AUTH_ENABLED = "dev.getelements.elements.auth.enabled";

    /** REST root — served at /element/quizpit/rest/... */
    @ElementDefaultAttribute("/element/quizpit/rest")
    public static final String RS_ROOT = "dev.getelements.elements.element.rs.root";

    /** WebSocket root — @ServerEndpoint("/stage") lands at /element/quizpit/ws/stage */
    @ElementDefaultAttribute("/element/quizpit/ws")
    public static final String WS_ROOT = "dev.getelements.elements.element.ws.root";

    @ElementDefaultAttribute("/app/static/quiz-pit")
    public static final String STATIC_CONTENT_URI = "dev.getelements.element.static.uri";

    @ElementDefaultAttribute("/app/ui/quiz-pit")
    public static final String UI_CONTENT_URI = "dev.getelements.element.ui.uri";

    @Override
    public Set<Class<?>> getClasses() {
        return Set.of(
                QuizRest.class,
                OpenApiResource.class
        );
    }

}
