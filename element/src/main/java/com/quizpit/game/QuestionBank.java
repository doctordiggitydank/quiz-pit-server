package com.quizpit.game;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The trivia question pack, loaded from questions.json on the element
 * classpath. A question: category, text, exactly four choices, correctIndex.
 * One shuffled deck per show (Fisher-Yates), consumed in order.
 */
public class QuestionBank {

    private static final Logger logger = LoggerFactory.getLogger(QuestionBank.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** One trivia question. */
    public record Question(String id, String category, String text,
                           List<String> choices, int correctIndex) {}

    private volatile List<Question> all = List.of();

    public QuestionBank() {
        reload();
    }

    /** Loads questions.json from the element classpath. */
    public final void reload() {
        final List<Question> loaded = new ArrayList<>();
        try (final InputStream in = QuestionBank.class.getResourceAsStream("/questions.json")) {
            if (in == null) {
                logger.warn("questions.json not on classpath — question bank empty");
            } else {
                final JsonNode root = MAPPER.readTree(in);
                int n = 0;
                for (final JsonNode node : root.path("questions")) {
                    final List<String> choices = new ArrayList<>();
                    node.path("choices").forEach(c -> choices.add(c.asText()));
                    if (choices.size() != 4) {
                        logger.warn("Question {} skipped: {} choices (need 4)", n, choices.size());
                        continue;
                    }
                    loaded.add(new Question(
                            node.path("id").asText("q" + n),
                            node.path("category").asText("Trivia"),
                            node.path("text").asText(),
                            List.copyOf(choices),
                            node.path("correctIndex").asInt(0)));
                    n++;
                }
            }
        } catch (final Exception e) {
            logger.error("questions.json failed to load: {}", e.getMessage());
        }
        this.all = List.copyOf(loaded);
        logger.info("Question bank loaded: {} questions", all.size());
    }

    public int size() {
        return all.size();
    }

    /** A shuffled deck for one show. */
    public List<Question> shuffledDeck() {
        final List<Question> deck = new ArrayList<>(all);
        Collections.shuffle(deck);
        return deck;
    }

}
