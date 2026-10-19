package com.quizpit.api.service;

/**
 * Exported surface of The Quiz Pit element. Other Elements (a future Twitch
 * Extension backend, an aggregator dashboard) can resolve this through the
 * ElementRegistry to read the live show state.
 */
public interface QuizPitService {

    /** The current show phase, e.g. LOBBY / QUESTION / REVEAL / GAME_OVER. */
    String phase();

    /** True when a broadcaster Twitch account is linked for this session. */
    boolean twitchLinked();

}
