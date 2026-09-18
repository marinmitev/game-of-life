package life.net;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import life.core.PatternFile;
import life.core.PatternFormatException;
import life.core.Universe;
import life.net.Message.Clear;
import life.net.Message.Error;
import life.net.Message.Load;
import life.net.Message.Notice;
import life.net.Message.Patterns;
import life.net.Message.Save;
import life.net.Message.SetSpeed;
import life.net.Message.Start;
import life.net.Message.State;
import life.net.Message.Step;
import life.net.Message.Stop;
import life.net.Message.Toggle;

/**
 * The single shared game: the current universe plus whether it is running and how fast.
 *
 * <p>This is the one mutable object in the program. Every method is {@code synchronized}, so the
 * tick thread and any number of client threads see a consistent state, and each command produces
 * exactly one reply describing what happened.
 *
 * <p>Saving and loading happen while the lock is held, which makes a load atomic with respect to
 * the simulation: no client can ever see half of a loaded pattern. Pattern files are small and a
 * generation is at least ten milliseconds away, so the pause is not worth trading that guarantee
 * for.
 */
public final class Game {

    public static final int MIN_SPEED_MILLIS = 10;
    public static final int MAX_SPEED_MILLIS = 5_000;
    public static final int DEFAULT_SPEED_MILLIS = 100;

    private final Path patternsDirectory;

    private Universe universe = Universe.empty();
    private boolean running;
    private int speedMillis = DEFAULT_SPEED_MILLIS;

    public Game(Path patternsDirectory) {
        this.patternsDirectory = patternsDirectory;
    }

    /** The current state, as sent to clients. */
    public synchronized State state() {
        return State.of(universe, running, speedMillis);
    }

    public synchronized boolean isRunning() {
        return running;
    }

    public synchronized int speedMillis() {
        return speedMillis;
    }

    /**
     * Carries out {@code command}.
     *
     * @return a {@link State} when the shared state changed and every client must be told, a
     *     {@link Notice} or {@link Error} when only the client that sent the command is interested
     */
    public synchronized Message apply(Message command) {
        return switch (command) {
            case Toggle toggle -> {
                universe = universe.toggle(toggle.cell());
                yield state();
            }
            case Start() -> {
                running = true;
                yield state();
            }
            case Stop() -> {
                running = false;
                yield state();
            }
            case Step() -> {
                universe = universe.next();
                yield state();
            }
            case Clear() -> {
                universe = Universe.empty();
                yield state();
            }
            case SetSpeed(int millis) -> {
                speedMillis = Math.clamp(millis, MIN_SPEED_MILLIS, MAX_SPEED_MILLIS);
                yield state();
            }
            case Patterns() -> {
                List<String> names = patternNames();
                yield new Notice(names.isEmpty()
                        ? "no saved patterns yet"
                        : "patterns: " + String.join(", ", names));
            }
            case Save(String name) -> save(name);
            case Load(String name) -> load(name);
            // Server-to-client messages are never commands. Listing them instead of using a
            // default branch keeps the switch exhaustive, so a new command cannot be forgotten.
            case State _ -> notACommand(command);
            case Notice _ -> notACommand(command);
            case Error _ -> notACommand(command);
        };
    }

    /**
     * Advances one generation if the game is running.
     *
     * <p>Returning empty when it is not lets the tick thread lose the race against a {@code stop}
     * without advancing a generation nobody asked for.
     */
    public synchronized Optional<State> tick() {
        if (!running) {
            return Optional.empty();
        }
        universe = universe.next();
        return Optional.of(state());
    }

    /** Names of the patterns that can be loaded, in alphabetical order. */
    public List<String> patternNames() {
        if (!Files.isDirectory(patternsDirectory)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(patternsDirectory)) {
            return files.map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(PatternFile.EXTENSION))
                    .map(name -> name.substring(0, name.length() - PatternFile.EXTENSION.length()))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Replaces the universe with the pattern stored under {@code name}. */
    public synchronized Message load(String name) {
        try {
            universe = PatternFile.load(fileFor(name));
            return state();
        } catch (NoSuchFileException e) {
            return new Error("no pattern named '" + name + "'");
        } catch (IOException e) {
            return new Error("could not read '" + name + "': " + e.getMessage());
        } catch (PatternFormatException e) {
            return new Error("'" + name + "' is not a valid pattern: " + e.getMessage());
        }
    }

    private Message save(String name) {
        try {
            PatternFile.save(universe, fileFor(name));
            return new Notice("saved " + universe.population() + " cells as '" + name + "'");
        } catch (IOException e) {
            return new Error("could not write '" + name + "': " + e.getMessage());
        } catch (PatternFormatException e) {
            return new Error("could not save '" + name + "': " + e.getMessage());
        }
    }

    /**
     * The file holding the pattern called {@code name}. The name is validated by
     * {@link Message.Save} and {@link Message.Load} and can therefore contain no path separators,
     * so the result always stays inside the patterns directory.
     */
    private Path fileFor(String name) {
        return patternsDirectory.resolve(name + PatternFile.EXTENSION);
    }

    private static Error notACommand(Message message) {
        return new Error("'" + Wire.encode(message) + "' is not a command");
    }
}
