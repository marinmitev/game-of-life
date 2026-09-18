package life.core;

import static life.TestPatterns.GLIDER;
import static life.TestPatterns.advance;
import static life.TestPatterns.cells;
import static life.TestPatterns.universe;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PatternFileTest {

    @Test
    void parsesGridCharactersRelativeToTheOrigin() {
        Universe universe = PatternFile.parse("""
                !Name: glider
                !Origin: 10 20
                .O.
                ..O
                OOO
                """);

        assertEquals(cells(10, 20, GLIDER), universe.alive());
        assertEquals(0, universe.generation());
    }

    @Test
    void defaultsOriginAndGenerationWhenHeadersAreAbsent() {
        Universe universe = PatternFile.parse(".O.\n..O\nOOO\n");

        assertEquals(cells(0, 0, GLIDER), universe.alive());
        assertEquals(0, universe.generation());
    }

    @Test
    void restoresTheGenerationCounter() {
        assertEquals(4321, PatternFile.parse("!Generation: 4321\nOO\nOO\n").generation());
    }

    @Test
    void acceptsAlternativeAliveAndDeadCharactersAndWindowsLineEndings() {
        Universe universe = PatternFile.parse(".o.\r\n..*\r\nOOO\r\n");

        assertEquals(cells(0, 0, GLIDER), universe.alive());
    }

    @Test
    void treatsBlankLinesAsEmptyRowsAndIgnoresComments() {
        Universe universe = PatternFile.parse("O\n\n!a comment between rows\nO\n");

        assertEquals(Set.of(new Cell(0, 0), new Cell(0, 2)), universe.alive());
    }

    @Test
    void commentOnlyFileIsAnEmptyUniverse() {
        assertEquals(Universe.empty(), PatternFile.parse("!nothing here\n!at all\n"));
    }

    @Test
    void rejectsUnknownGridCharactersReportingTheLine() {
        PatternFormatException thrown =
                assertThrows(PatternFormatException.class, () -> PatternFile.parse("OO\nO?O\n"));

        assertTrue(thrown.getMessage().contains("line 2"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("column 2"), thrown.getMessage());
    }

    @Test
    void rejectsUnparsableHeaderNumbers() {
        assertThrows(PatternFormatException.class, () -> PatternFile.parse("!Origin: x 0\nO\n"));
        assertThrows(PatternFormatException.class, () -> PatternFile.parse("!Generation: soon\nO\n"));
    }

    @Test
    void roundTripsThroughTextForRepresentativeUniverses() {
        assertRoundTrips(Universe.empty());
        assertRoundTrips(universe(0, 0, GLIDER));
        assertRoundTrips(advance(universe(-18, -4, GLIDER), 17));
        assertRoundTrips(Universe.of(Set.of(new Cell(Long.MIN_VALUE, Long.MAX_VALUE))));
    }

    @Test
    void roundTripsAPatternStraddlingBothWrapSeams() {
        Universe straddling = universe(Long.MAX_VALUE - 1, Long.MAX_VALUE - 1, GLIDER);

        assertRoundTrips(straddling);
        // The pattern is written as a compact 3-row grid, not as a universe-sized rectangle.
        assertEquals(3, PatternFile.format(straddling).lines().filter(l -> !l.startsWith("!")).count());
    }

    @Test
    void refusesToWriteCellsSpreadTooFarApart() {
        Universe farApart = Universe.of(Set.of(new Cell(0, 0), new Cell(PatternFile.MAX_SPAN, 0)));

        PatternFormatException thrown =
                assertThrows(PatternFormatException.class, () -> PatternFile.format(farApart));
        assertTrue(thrown.getMessage().contains("width"), thrown.getMessage());
    }

    @Test
    void savesAndLoadsThroughTheFileSystem(@TempDir Path directory) throws IOException {
        Universe universe = advance(universe(5, 5, GLIDER), 3);
        Path file = directory.resolve("nested").resolve("glider" + PatternFile.EXTENSION);

        PatternFile.save(universe, file);

        assertEquals(universe, PatternFile.load(file));
    }

    @Test
    void exampleGliderGunFileIsTheRealGosperGun() throws IOException {
        Universe gun = PatternFile.load(Path.of("patterns", "gosper-glider-gun.cells"));

        assertEquals(36, gun.population());
        // Centred on the origin, so it is visible in the client's default viewport.
        assertTrue(gun.isAlive(new Cell(-18, 0)), "expected the left block at x=-18");
        assertTrue(gun.isAlive(new Cell(17, -2)), "expected the right block at x=17");

        // The gun has period 30: after 30 generations it is back to its initial shape and has
        // emitted exactly one glider.
        Universe after30 = advance(gun, 30);
        assertTrue(after30.alive().containsAll(gun.alive()), "the gun should reappear unchanged");
        assertEquals(41, after30.population(), "the gun plus one five-cell glider");

        Set<Cell> firstGlider = new HashSet<>(after30.alive());
        firstGlider.removeAll(gun.alive());
        assertEquals(5, firstGlider.size());
        // Unbounded growth: exactly one more five-cell glider for every further 30 generations.
        assertEquals(46, advance(gun, 60).population(), "a second glider joins the first");
        assertEquals(71, advance(gun, 210).population(), "seven gliders after seven periods");
    }

    private static void assertRoundTrips(Universe universe) {
        assertEquals(universe, PatternFile.parse(PatternFile.format(universe)));
    }
}
