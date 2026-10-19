package com.quizpit.guice;

import com.google.inject.PrivateModule;
import com.quizpit.api.service.QuizPitService;
import com.quizpit.game.QuestionBank;
import com.quizpit.game.QuizGame;
import com.quizpit.service.GuestStarService;
import com.quizpit.service.ProfileService;
import com.quizpit.twitch.HelixClient;
import com.quizpit.twitch.TwitchGateway;

/**
 * Quiz Pit bindings. Everything is a singleton: one gateway, one guest-star
 * roster, one trivia brain. The WS endpoints resolve these through the
 * service locator (Jersey instantiates endpoints, Guice bridges services).
 */
public class QuizPitModule extends PrivateModule {

    @Override
    protected void configure() {

        bind(HelixClient.class).asEagerSingleton();
        bind(QuestionBank.class).asEagerSingleton();
        bind(ProfileService.class).asEagerSingleton();
        bind(GuestStarService.class).asEagerSingleton();
        bind(TwitchGateway.class).asEagerSingleton();
        bind(QuizGame.class).asEagerSingleton();

        bind(QuizPitService.class).to(QuizGame.class);
        expose(QuizPitService.class);

    }

}
