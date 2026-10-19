package com.quizpit.game;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ChatAnswerParsingTest {

    @Test
    void numericChoices() {
        assertEquals(0, QuizGame.parseChoice("!1"));
        assertEquals(1, QuizGame.parseChoice("!2"));
        assertEquals(2, QuizGame.parseChoice("!3"));
        assertEquals(3, QuizGame.parseChoice("!4"));
    }

    @Test
    void letterChoices() {
        assertEquals(0, QuizGame.parseChoice("!a"));
        assertEquals(1, QuizGame.parseChoice("!B"));
        assertEquals(2, QuizGame.parseChoice("!c"));
        assertEquals(3, QuizGame.parseChoice("!D"));
    }

    @Test
    void whitespaceTolerant() {
        assertEquals(2, QuizGame.parseChoice("  !3 "));
    }

    @Test
    void nonAnswers() {
        assertEquals(-1, QuizGame.parseChoice("hello"));
        assertEquals(-1, QuizGame.parseChoice("!"));
        assertEquals(-1, QuizGame.parseChoice("!5"));
        assertEquals(-1, QuizGame.parseChoice("!42"));
        assertEquals(-1, QuizGame.parseChoice("!e"));
        assertEquals(-1, QuizGame.parseChoice(null));
        assertEquals(-1, QuizGame.parseChoice(""));
        assertEquals(-1, QuizGame.parseChoice("!!1"));
    }

}
