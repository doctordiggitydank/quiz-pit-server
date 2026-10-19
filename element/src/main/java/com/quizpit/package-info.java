// Required annotation for an Element. Will recursively search folders
// from this point to include classes in the Element if recursive is true.
@ElementDefinition(recursive = true)
// Enables DI via Guice
@GuiceElementModule(QuizPitModule.class)
// Allows injecting DAO layer from Elements Core
@ElementDependency("dev.getelements.elements.sdk.dao")
// Allows injecting Service layer from Elements Core
@ElementDependency("dev.getelements.elements.sdk.service")
package com.quizpit;

import com.quizpit.guice.QuizPitModule;
import dev.getelements.elements.sdk.annotation.ElementDefinition;
import dev.getelements.elements.sdk.annotation.ElementDependency;
import dev.getelements.elements.sdk.spi.guice.annotations.GuiceElementModule;

// The Quiz Pit — a Twitch-streamed trivia show. The UE 5.8.3 broadcast client
// connects at /element/quizpit/ws/stage; the web host panel at
// /element/quizpit/ws/host. Twitch integration (chat answers, cheers, and
// StreamTogether/Guest Star guest tracking) runs server-side so the tokens
// never touch the client.
