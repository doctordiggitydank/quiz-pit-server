package com.quizpit.game;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class QuestionBankTest {

    @Test
    void loadsTheRealPack() {
        final QuestionBank bank = new QuestionBank();
        assertTrue(bank.size() > 900, "expected the full sheet bank, got " + bank.size());
    }

    @Test
    void everyQuestionHasFourChoicesAndAValidCorrectIndex() {
        final QuestionBank bank = new QuestionBank();
        final List<QuestionBank.Question> deck = bank.shuffledDeck();
        assertEquals(bank.size(), deck.size(), "shuffled deck must contain every question");
        for (final QuestionBank.Question q : deck) {
            assertEquals(4, q.choices().size(), "question " + q.id() + " must have 4 choices");
            assertTrue(q.correctIndex() >= 0 && q.correctIndex() < 4,
                    "question " + q.id() + " correctIndex out of range");
        }
    }

    @Test
    void deckIsShuffled() {
        final QuestionBank bank = new QuestionBank();
        // 962 cards: the odds of the first three shuffling into sheet order are
        // negligible — but don't make the test flaky on a fixed order either;
        // assert two consecutive decks differ somewhere with near-certainty.
        final List<QuestionBank.Question> a = bank.shuffledDeck();
        final List<QuestionBank.Question> b = bank.shuffledDeck();
        assertNotEquals(a, b, "two consecutive decks should not be identical");
    }

}
