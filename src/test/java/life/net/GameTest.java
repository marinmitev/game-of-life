package life.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import life.TestPatterns;
import life.core.Cell;
import life.core.PatternFile;
import life.core.Universe;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GameTest {

    @TempDir
    Path patterns;

    private Game game;

    @BeforeEach
    void setUp() {
        game = new Game(patterns);
    }

    @Test
    void startsEmptyPausedAndAtTheDefaultSpeed() {
        Message.State state = game.state();

        assertEquals(0, state.generation());
        assertEquals(0, state.population());
        assertFalse(state.running());
        assertEquals(Game.DEFAULT_SPEED_MILLIS, state.speedMillis());
    }

    @Test
    void toggleAddsAndRemovesACell() {
        assertEquals(Set.of(new Cell(4, 9)), state(game.apply(new Message.Toggle(4, 9))).alive());
        assertEquals(Set.of(), state(game.apply(new Message.Toggle(4, 9))).alive());
    }

    @Test
    void startAndStopControlTheRunningFlag() {
        assertTrue(state(game.apply(new Message.Start())).running());
        assertTrue(game.isRunning());

        assertFalse(state(game.apply(new Message.Stop())).running());
        assertFalse(game.isRunning());
    }

    @Test
    void stepAdvancesExactlyOneGeneration() {
        seedBlinker();

        assertEquals(1, state(game.apply(new Message.Step())).generation());
        assertEquals(2, state(game.apply(new Message.Step())).generation());
        assertEquals(3, game.state().population());
    }

    @Test
    void clearEmptiesTheUniverseAndResetsTheGeneration() {
        seedBlinker();
        game.apply(new Message.Step());

        Message.State cleared = state(game.apply(new Message.Clear()));

        assertEquals(0, cleared.population());
        assertEquals(0, cleared.generation());
    }

    @Test
    void speedIsClampedToASensibleRange() {
        assertEquals(Game.MIN_SPEED_MILLIS, state(game.apply(new Message.SetSpeed(0))).speedMillis());
        assertEquals(
                Game.MAX_SPEED_MILLIS,
                state(game.apply(new Message.SetSpeed(Integer.MAX_VALUE))).speedMillis());
        assertEquals(250, state(game.apply(new Message.SetSpeed(250))).speedMillis());
    }

    @Test
    void tickAdvancesOnlyWhileRunning() {
        seedBlinker();

        assertEquals(Optional.empty(), game.tick());

        game.apply(new Message.Start());
        assertEquals(1, game.tick().orElseThrow().generation());
    }

    @Test
    void saveThenLoadRestoresTheState() {
        seedBlinker();
        game.apply(new Message.Step());
        Message.State saved = game.state();

        Message reply = game.apply(new Message.Save("blinker"));
        assertInstanceOf(Message.Notice.class, reply);
        assertTrue(Files.exists(patterns.resolve("blinker.cells")));

        game.apply(new Message.Clear());
        assertEquals(0, game.state().population());

        assertEquals(saved, state(game.apply(new Message.Load("blinker"))));
    }

    @Test
    void loadingAMissingPatternFailsWithoutChangingAnything() {
        seedBlinker();
        Message.State before = game.state();

        Message reply = game.apply(new Message.Load("nothing-here"));

        assertEquals(new Message.Error("no pattern named 'nothing-here'"), reply);
        assertEquals(before, game.state());
    }

    @Test
    void loadingACorruptPatternFailsWithoutChangingAnything() throws IOException {
        seedBlinker();
        Message.State before = game.state();
        Files.writeString(patterns.resolve("broken.cells"), "OO\nO?O\n");

        Message reply = game.apply(new Message.Load("broken"));

        assertInstanceOf(Message.Error.class, reply);
        assertTrue(((Message.Error) reply).text().contains("line 2"));
        assertEquals(before, game.state());
    }

    @Test
    void savingCellsSpreadTooFarApartFailsWithAnExplanation() {
        game.apply(new Message.Toggle(0, 0));
        game.apply(new Message.Toggle(1_000_000, 0));

        Message reply = game.apply(new Message.Save("too-wide"));

        assertInstanceOf(Message.Error.class, reply);
        assertTrue(((Message.Error) reply).text().contains("could not save"));
    }

    @Test
    void listsAvailablePatternNamesWithoutTheirExtension() throws IOException {
        Files.writeString(patterns.resolve("second.cells"), "O\n");
        Files.writeString(patterns.resolve("first.cells"), "O\n");
        Files.writeString(patterns.resolve("notes.txt"), "ignored");

        assertEquals(List.of("first", "second"), game.patternNames());
    }

    @Test
    void listingPatternsOfAMissingDirectoryIsEmpty() {
        assertEquals(List.of(), new Game(patterns.resolve("absent")).patternNames());
    }

    @Test
    void serverToClientMessagesAreRejectedAsCommands() {
        Message reply = game.apply(new Message.Notice("hello"));

        assertInstanceOf(Message.Error.class, reply);
        assertTrue(((Message.Error) reply).text().contains("not a command"));
    }

    @Test
    void loadsTheExampleGliderGunFromTheProjectDirectory() throws IOException {
        Universe gun = PatternFile.load(Path.of("patterns", "gosper-glider-gun.cells"));
        PatternFile.save(gun, patterns.resolve("gun.cells"));

        assertEquals(36, state(game.apply(new Message.Load("gun"))).population());
    }

    private void seedBlinker() {
        TestPatterns.cells(0, 0, TestPatterns.BLINKER)
                .forEach(cell -> game.apply(new Message.Toggle(cell.x(), cell.y())));
    }

    private static Message.State state(Message message) {
        return assertInstanceOf(Message.State.class, message);
    }
}
