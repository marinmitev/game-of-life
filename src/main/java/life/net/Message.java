package life.net;

import java.util.Set;
import java.util.regex.Pattern;
import life.core.Cell;
import life.core.Universe;

/**
 * Everything that can travel between a client and the server.
 *
 * <p>The hierarchy is sealed so that {@link Wire} and the server's command handler can switch over
 * it exhaustively: adding a message is a compile error everywhere it has to be handled.
 */
public sealed interface Message {

    /** Names of saved patterns are used as file names, so they are deliberately restrictive. */
    Pattern VALID_NAME = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    /** Flip the state of one cell. */
    record Toggle(long x, long y) implements Message {
        public Cell cell() {
            return new Cell(x, y);
        }
    }

    /** Begin advancing generations automatically. */
    record Start() implements Message {
    }

    /** Stop advancing generations automatically. */
    record Stop() implements Message {
    }

    /** Advance exactly one generation. */
    record Step() implements Message {
    }

    /** Kill every cell. */
    record Clear() implements Message {
    }

    /** Change the delay between generations. */
    record SetSpeed(int millis) implements Message {
    }

    /** Ask which patterns the server has stored. */
    record Patterns() implements Message {
    }

    /** Store the current state under {@code name}. */
    record Save(String name) implements Message {
        public Save {
            requireValidName(name);
        }
    }

    /** Replace the current state with the one stored under {@code name}. */
    record Load(String name) implements Message {
        public Load {
            requireValidName(name);
        }
    }

    /**
     * The complete shared state, sent to every client whenever it changes.
     *
     * <p>Sending the whole live set rather than a delta keeps both ends stateless and lets a client
     * join a running game at any moment; it costs a few bytes per live cell per generation.
     */
    record State(long generation, boolean running, int speedMillis, Set<Cell> alive)
            implements Message {

        public State {
            alive = Set.copyOf(alive);
        }

        public static State of(Universe universe, boolean running, int speedMillis) {
            return new State(universe.generation(), running, speedMillis, universe.alive());
        }

        public int population() {
            return alive.size();
        }
    }

    /** Confirmation of something that changed nothing observable, such as a successful save. */
    record Notice(String text) implements Message {
    }

    /** A command that could not be carried out; sent only to the client that caused it. */
    record Error(String text) implements Message {
    }

    private static void requireValidName(String name) {
        if (name == null || !VALID_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException(
                    "pattern name must match " + VALID_NAME.pattern() + " but was '" + name + "'");
        }
    }
}
