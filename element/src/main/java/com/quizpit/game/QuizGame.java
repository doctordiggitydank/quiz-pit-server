package com.quizpit.game;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quizpit.api.service.QuizPitService;
import com.quizpit.service.GuestStarService;
import com.quizpit.service.ProfileService;
import com.quizpit.twitch.TwitchGateway;
import dev.getelements.elements.sdk.Element;
import dev.getelements.elements.sdk.ElementSupplier;
import jakarta.websocket.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * The Quiz Pit trivia brain. Phases:
 *
 * <pre>LOBBY -> QUESTION (answers open, timer) -> REVEAL (correct + points)
 *      -> QUESTION ... -> host_end -> GAME_OVER</pre>
 *
 * Viewers answer over Twitch chat ("!1".."!4"/"!a".."!d"); the first answer
 * per Twitch user wins (later ones ignored). Points: 100 for a correct answer
 * plus a speed bonus up to 50. The UE stage (one socket) and any number of web
 * host-panel sockets receive full state pushes and per-second ticks.
 */
public class QuizGame implements QuizPitService {

    private static final Logger logger = LoggerFactory.getLogger(QuizGame.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public enum Phase { LOBBY, QUESTION, REVEAL, GAME_OVER }

    /** One viewer's score line. */
    public record Player(String userId, String username, String login,
                         int score, int correctCount) {}

    /** One accepted (or late-rejected) answer, for the reveal. */
    public record Answer(String userId, String username, int choice,
                         boolean correct, int points) {}

    private static final int BASE_POINTS = 100;
    private static final int MAX_SPEED_BONUS = 50;

    private final TwitchGateway gateway;
    private final GuestStarService guestStars;
    private final ProfileService profiles;
    private final QuestionBank bank;

    private final ScheduledExecutorService timer =
            Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "quizpit-timer"));

    private final Object lock = new Object();

    private Phase phase = Phase.LOBBY;
    private List<QuestionBank.Question> deck = List.of();
    private int questionIndex = -1;
    private int answerSeconds = 20;
    private int secondsLeft;
    private ScheduledFuture<?> tick;

    private final Map<String, Player> players = new LinkedHashMap<>();
    private final Map<String, Answer> answers = new LinkedHashMap<>();
    private Map<String, Object> lastReveal;

    // --- sockets ---
    private volatile Session stageSocket;
    private final List<Session> hostSockets = new CopyOnWriteArrayList<>();

    public QuizGame(final TwitchGateway gateway, final GuestStarService guestStars,
                    final ProfileService profiles, final QuestionBank bank) {
        this.gateway = gateway;
        this.guestStars = guestStars;
        this.profiles = profiles;
        this.bank = bank;

        gateway.setChatConsumer(this::onChat);
        gateway.setCheerConsumer(this::onCheer);
        guestStars.setRosterListener(roster -> broadcastGuests());

        try {
            final Element element = ElementSupplier.getElementLocal(QuizGame.class).get();
            this.answerSeconds = element.getElementRecord().attributes()
                    .getAttributeOptional("quiz.answer.seconds")
                    .map(Object::toString).map(s -> {
                        try { return Math.max(5, Math.min(120, Integer.parseInt(s))); }
                        catch (NumberFormatException e) { return 20; }
                    }).orElse(20);
        } catch (final Exception ignored) { }

        logger.info("Quiz Pit game up: {} questions, {}s answer window", bank.size(), answerSeconds);
    }

    // ------------------------------------------------------------------
    // Sockets
    // ------------------------------------------------------------------

    /** One stage at a time — a new connect takes the slot (DoD rule). */
    public void registerStage(final Session socket) {
        final Session old = stageSocket;
        stageSocket = socket;
        if (old != null && !old.getId().equals(socket.getId())) {
            try { old.close(); } catch (Exception ignored) { }
        }
        logger.info("Stage connected ({})", socket.getId());
        sendHello(socket);
        broadcastState();
    }

    public void unregisterStage(final Session socket) {
        if (stageSocket != null && stageSocket.getId().equals(socket.getId())) {
            stageSocket = null;
            logger.info("Stage disconnected");
        }
    }

    public void registerHost(final Session socket) {
        hostSockets.add(socket);
        sendHello(socket);
        broadcastState();
    }

    public void unregisterHost(final Session socket) {
        hostSockets.remove(socket);
    }

    private void sendHello(final Session socket) {
        final Map<String, Object> hello = new LinkedHashMap<>();
        hello.put("type", "hello");
        final TwitchGateway.LinkStatus link = gateway.linkStatus();
        hello.put("twitch", Map.of(
                "linked", link.linked(),
                "login", link.login() == null ? "" : link.login(),
                "clientId", link.clientId() == null ? "" : link.clientId(),
                "scopes", link.scopes()));
        hello.put("guests", GuestStarService.toMaps(guestStars.roster()));
        send(socket, hello);
    }

    // ------------------------------------------------------------------
    // Chat / cheers
    // ------------------------------------------------------------------

    private void onChat(final TwitchGateway.ChatAnswer chat) {
        final int choice = parseChoice(chat.message());
        if (choice < 0 || phase != Phase.QUESTION) {
            return;
        }
        synchronized (lock) {
            if (answers.containsKey(chat.userId())) {
                return; // first answer wins
            }
            final QuestionBank.Question q = deck.get(questionIndex);
            final boolean correct = choice == q.correctIndex();
            final long elapsed = answerSeconds - secondsLeft;
            final int bonus = Math.max(0, MAX_SPEED_BONUS
                    - (int) (MAX_SPEED_BONUS * Math.min(answerSeconds, elapsed) / (double) answerSeconds));
            final int points = correct ? BASE_POINTS + bonus : 0;
            answers.put(chat.userId(), new Answer(chat.userId(), chat.username(), choice, correct, points));

            final Player prev = players.get(chat.userId());
            final Player now = new Player(chat.userId(), chat.username(), chat.username(),
                    (prev == null ? 0 : prev.score()) + points,
                    (prev == null ? 0 : prev.correctCount()) + (correct ? 1 : 0));
            players.put(chat.userId(), now);

            broadcast(Map.of(
                    "type", "chat_answer",
                    "userId", chat.userId(),
                    "username", chat.username(),
                    "choice", choice,
                    "answered", answers.size()));
        }
        profiles.resolve(List.of(chat.userId()));
    }

    private void onCheer(final TwitchGateway.Cheer cheer) {
        broadcast(Map.of(
                "type", "cheer",
                "username", cheer.username() == null || cheer.username().isEmpty()
                        ? "anonymous" : cheer.username(),
                "bits", cheer.bits()));
    }

    /** "!1".."!4" or "!a".."!d", case-insensitive; -1 = not an answer.
     *  Strict: anything after the choice character ("!42", "!1a") is NOT an
     *  answer — chat is full of lookalikes and a wrong match scores points. */
    static int parseChoice(final String message) {
        if (message == null) return -1;
        final String m = message.trim().toLowerCase();
        if (!m.startsWith("!") || m.length() != 2) return -1;
        return switch (m.charAt(1)) {
            case '1', 'a' -> 0;
            case '2', 'b' -> 1;
            case '3', 'c' -> 2;
            case '4', 'd' -> 3;
            default -> -1;
        };
    }

    // ------------------------------------------------------------------
    // Host commands
    // ------------------------------------------------------------------

    /** Handles a message from the stage or a host panel socket. */
    public void onClientMessage(final Session socket, final Map<String, Object> message) {
        final String type = String.valueOf(message.get("type"));
        switch (type) {
            case "ping" -> send(socket, Map.of("type", "pong"));
            case "twitch_link" -> gateway.linkHost(
                    str(message.get("login")), str(message.get("userId")),
                    str(message.get("accessToken")), str(message.get("refreshToken")));
            case "twitch_unlink" -> gateway.unlinkHost();
            case "host_start" -> hostStart();
            case "host_next" -> hostNext();
            case "host_reveal" -> hostReveal();
            case "host_end" -> hostEnd();
            case "host_reset" -> hostReset();
            default -> send(socket, Map.of("type", "host_error",
                    "message", "Unknown message " + type));
        }
    }

    public void hostStart() {
        synchronized (lock) {
            deck = bank.shuffledDeck();
            if (deck.isEmpty()) {
                broadcast(Map.of("type", "host_error", "message", "Question bank is empty"));
                return;
            }
            players.clear();
            answers.clear();
            lastReveal = null;
            phase = Phase.QUESTION;
            questionIndex = -1;
            broadcast(Map.of("type", "show_start", "questions", deck.size()));
        }
        hostNext();
    }

    public void hostNext() {
        synchronized (lock) {
            if (phase == Phase.GAME_OVER || questionIndex + 1 >= deck.size()) {
                hostEnd();
                return;
            }
            questionIndex++;
            answers.clear();
            lastReveal = null;
            phase = Phase.QUESTION;
            secondsLeft = answerSeconds;
            final QuestionBank.Question q = deck.get(questionIndex);
            logger.info("Question {}/{} open: {}", questionIndex + 1, deck.size(), q.text());
        }
        broadcastState();
        startTimer();
    }

    public void hostReveal() {
        stopTimer();
        reveal();
    }

    public void hostEnd() {
        stopTimer();
        synchronized (lock) {
            phase = Phase.GAME_OVER;
        }
        broadcastState();
    }

    public void hostReset() {
        stopTimer();
        synchronized (lock) {
            phase = Phase.LOBBY;
            questionIndex = -1;
            answers.clear();
            lastReveal = null;
        }
        broadcastState();
    }

    private void startTimer() {
        stopTimer();
        tick = timer.scheduleAtFixedRate(() -> {
            synchronized (lock) {
                secondsLeft--;
                if (secondsLeft <= 0) {
                    reveal();
                }
            }
            broadcast(Map.of("type", "tick", "secondsLeft", secondsLeft));
        }, 1, 1, TimeUnit.SECONDS);
    }

    private void stopTimer() {
        final ScheduledFuture<?> t = tick;
        if (t != null) {
            t.cancel(false);
            tick = null;
        }
    }

    /** Closes the question: computes results, awards points (already added at
     *  answer time — the reveal re-sends them), flips to REVEAL. */
    private void reveal() {
        synchronized (lock) {
            if (phase != Phase.QUESTION) {
                return;
            }
            final QuestionBank.Question q = deck.get(questionIndex);
            final List<Map<String, Object>> answerMaps = new ArrayList<>();
            for (final Answer a : answers.values()) {
                answerMaps.add(Map.of(
                        "userId", a.userId(),
                        "username", a.username(),
                        "choice", a.choice(),
                        "correct", a.correct(),
                        "points", a.points()));
            }
            lastReveal = Map.of(
                    "questionId", q.id(),
                    "correctIndex", q.correctIndex(),
                    "answers", answerMaps,
                    "answered", answers.size());
            phase = Phase.REVEAL;
            logger.info("Reveal: {} answered, correct index {}", answers.size(), q.correctIndex());
        }
        broadcastState();
    }

    // ------------------------------------------------------------------
    // Broadcasting
    // ------------------------------------------------------------------

    private void broadcastGuests() {
        final Map<String, Object> guests = Map.of(
                "type", "guests",
                "guests", GuestStarService.toMaps(guestStars.roster()));
        broadcast(guests);
    }

    /** Full state snapshot to every socket. */
    private void broadcastState() {
        final Map<String, Object> state = new LinkedHashMap<>();
        synchronized (lock) {
            state.put("type", "state");
            state.put("phase", phase.name());
            state.put("questionIndex", questionIndex);
            state.put("totalQuestions", deck.size());
            state.put("answerSeconds", answerSeconds);
            state.put("secondsLeft", secondsLeft);
            if (phase == Phase.QUESTION && questionIndex >= 0 && questionIndex < deck.size()) {
                final QuestionBank.Question q = deck.get(questionIndex);
                state.put("question", questionMap(q, false));
            }
            if (lastReveal != null && phase == Phase.REVEAL && questionIndex >= 0) {
                final QuestionBank.Question q = deck.get(questionIndex);
                state.put("question", questionMap(q, true));
                state.put("reveal", lastReveal);
            }
            state.put("leaderboard", leaderboardMaps(10));
        }
        broadcast(state);
    }

    private Map<String, Object> questionMap(final QuestionBank.Question q, final boolean withAnswer) {
        final Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", q.id());
        map.put("category", q.category());
        map.put("text", q.text());
        map.put("choices", q.choices());
        if (withAnswer) {
            map.put("correctIndex", q.correctIndex());
        }
        return map;
    }

    private List<Map<String, Object>> leaderboardMaps(final int limit) {
        final List<Player> board = new ArrayList<>(players.values());
        board.sort(Comparator.comparingInt(Player::score).reversed());
        final List<Map<String, Object>> maps = new ArrayList<>();
        int rank = 1;
        for (final Player p : board.subList(0, Math.min(limit, board.size()))) {
            maps.add(Map.of(
                    "rank", rank++,
                    "userId", p.userId(),
                    "username", p.username(),
                    "score", p.score(),
                    "correct", p.correctCount()));
        }
        return maps;
    }

    private void broadcast(final Map<String, Object> message) {
        final String text;
        try {
            text = MAPPER.writeValueAsString(message);
        } catch (final Exception e) {
            return;
        }
        final Session stage = stageSocket;
        if (stage != null && stage.isOpen()) {
            try { stage.getBasicRemote().sendText(text); } catch (Exception e) {
                logger.debug("stage send: {}", e.getMessage());
            }
        }
        for (final Session host : hostSockets) {
            if (host.isOpen()) {
                try { host.getBasicRemote().sendText(text); } catch (Exception e) {
                    logger.debug("host send: {}", e.getMessage());
                }
            }
        }
    }

    private void send(final Session socket, final Map<String, Object> message) {
        try {
            if (socket.isOpen()) {
                socket.getBasicRemote().sendText(MAPPER.writeValueAsString(message));
            }
        } catch (final Exception e) {
            logger.debug("send: {}", e.getMessage());
        }
    }

    private static String str(final Object o) {
        return o == null ? null : o.toString();
    }

    // ------------------------------------------------------------------
    // QuizPitService export
    // ------------------------------------------------------------------

    @Override
    public String phase() {
        return phase.name();
    }

    @Override
    public boolean twitchLinked() {
        return gateway.linkStatus().linked();
    }

    /** Player count (for REST status). */
    public int playerCount() {
        synchronized (lock) {
            return players.size();
        }
    }

}
