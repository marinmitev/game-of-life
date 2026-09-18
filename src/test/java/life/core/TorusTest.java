package life.core;

import static life.TestPatterns.BLINKER;
import static life.TestPatterns.GLIDER;
import static life.TestPatterns.advance;
import static life.TestPatterns.cells;
import static life.TestPatterns.shifted;
import static life.TestPatterns.universe;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The universe is a 2^64 x 2^64 torus. These tests run known patterns across the coordinate seam
 * between {@link Long#MAX_VALUE} and {@link Long#MIN_VALUE} and assert they behave exactly as they
 * do in the middle of the universe.
 */
class TorusTest {

    @ParameterizedTest(name = "blinker at ({0}, {1})")
    @CsvSource({
        "0, 0",
        "9223372036854775807, 0", // across the x seam
        "0, 9223372036854775807", // across the y seam
        "9223372036854775807, 9223372036854775807", // across both seams at once
        "-9223372036854775808, -9223372036854775808" // at the opposite corner
    })
    void blinkerOscillatesAnywhereOnTheTorus(long originX, long originY) {
        Universe horizontal = universe(originX, originY, BLINKER);

        Universe vertical = horizontal.next();
        assertEquals(3, vertical.population());
        assertEquals(cells(originX + 1, originY - 1, "O", "O", "O"), vertical.alive());
        assertEquals(horizontal.alive(), vertical.next().alive());
    }

    @Test
    void gliderCrossesTheSeamAndKeepsItsShape() {
        // Start four cells short of the far corner and fly diagonally across the wrap point.
        long start = Long.MAX_VALUE - 4;
        Universe glider = universe(start, start, GLIDER);

        Universe after40 = advance(glider, 40);

        assertEquals(5, after40.population());
        assertEquals(shifted(glider, 10, 10), after40.alive());
        // The glider is now on the other side of the seam.
        assertEquals(
                Set.of(
                        new Cell(Long.MIN_VALUE + 6, Long.MIN_VALUE + 5),
                        new Cell(Long.MIN_VALUE + 7, Long.MIN_VALUE + 6),
                        new Cell(Long.MIN_VALUE + 5, Long.MIN_VALUE + 7),
                        new Cell(Long.MIN_VALUE + 6, Long.MIN_VALUE + 7),
                        new Cell(Long.MIN_VALUE + 7, Long.MIN_VALUE + 7)),
                after40.alive());
    }

    @Test
    void oppositeEdgesOfTheUniverseAreAdjacent() {
        // A blinker whose three cells are the last two and the first column of the universe
        // collapses onto the seam itself, proving MAX_VALUE and MIN_VALUE are neighbours.
        Universe blinkerAcrossSeam =
                Universe.of(
                        Set.of(
                                new Cell(Long.MAX_VALUE - 1, 0),
                                new Cell(Long.MAX_VALUE, 0),
                                new Cell(Long.MIN_VALUE, 0)));

        assertEquals(
                Set.of(
                        new Cell(Long.MAX_VALUE, -1),
                        new Cell(Long.MAX_VALUE, 0),
                        new Cell(Long.MAX_VALUE, 1)),
                blinkerAcrossSeam.next().alive());
    }
}
