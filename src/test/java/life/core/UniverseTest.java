package life.core;

import static life.TestPatterns.BLINKER;
import static life.TestPatterns.BLOCK;
import static life.TestPatterns.GLIDER;
import static life.TestPatterns.advance;
import static life.TestPatterns.cells;
import static life.TestPatterns.shifted;
import static life.TestPatterns.universe;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;

class UniverseTest {

    @Test
    void emptyUniverseStaysEmpty() {
        Universe next = Universe.empty().next();

        assertEquals(Set.of(), next.alive());
        assertEquals(1, next.generation());
    }

    @Test
    void blockIsAStillLife() {
        Universe block = universe(0, 0, BLOCK);

        assertEquals(block.alive(), advance(block, 1).alive());
        assertEquals(block.alive(), advance(block, 10).alive());
    }

    @Test
    void blinkerOscillatesWithPeriodTwo() {
        Universe horizontal = universe(0, 0, BLINKER);

        Universe vertical = horizontal.next();
        assertEquals(cells(1, -1, "O", "O", "O"), vertical.alive());
        assertEquals(horizontal.alive(), vertical.next().alive());
    }

    @Test
    void gliderTranslatesDiagonallyEveryFourGenerations() {
        Universe glider = universe(0, 0, GLIDER);

        assertEquals(shifted(glider, 1, 1), advance(glider, 4).alive());
        assertEquals(shifted(glider, 5, 5), advance(glider, 20).alive());
        assertEquals(5, advance(glider, 20).population());
    }

    @Test
    void lonelyCellDiesOfUnderpopulation() {
        assertEquals(Set.of(), universe(0, 0, "O").next().alive());
        assertEquals(Set.of(), universe(0, 0, "OO").next().alive());
    }

    @Test
    void crowdedCellDiesOfOverpopulation() {
        // The centre of a 3x3 block has eight live neighbours and cannot survive.
        Universe filled = universe(0, 0, "OOO", "OOO", "OOO");

        assertFalse(filled.next().isAlive(new Cell(1, 1)));
    }

    @Test
    void generationIncrementsByExactlyOnePerStep() {
        Universe glider = universe(0, 0, GLIDER);

        assertEquals(0, glider.generation());
        assertEquals(1, glider.next().generation());
        assertEquals(7, advance(glider, 7).generation());
    }

    @Test
    void editsChangeCellsWithoutChangingTheGeneration() {
        Universe generationThree = advance(universe(0, 0, GLIDER), 3);

        Universe edited = generationThree.with(new Cell(100, 100));
        assertTrue(edited.isAlive(new Cell(100, 100)));
        assertEquals(3, edited.generation());

        Universe erased = edited.without(new Cell(100, 100));
        assertFalse(erased.isAlive(new Cell(100, 100)));
        assertEquals(generationThree.alive(), erased.alive());
    }

    @Test
    void toggleFlipsCellState() {
        Universe universe = Universe.empty().toggle(new Cell(5, 5));
        assertTrue(universe.isAlive(new Cell(5, 5)));

        assertFalse(universe.toggle(new Cell(5, 5)).isAlive(new Cell(5, 5)));
    }

    @Test
    void redundantEditsReturnTheSameInstance() {
        Universe glider = universe(0, 0, GLIDER);

        assertSame(glider, glider.with(new Cell(1, 0)));
        assertSame(glider, glider.without(new Cell(500, 500)));
        assertSame(Universe.empty(), Universe.of(Set.of()));
    }

    @Test
    void liveCellsAreUnmodifiable() {
        Universe glider = universe(0, 0, GLIDER);

        assertThrows(UnsupportedOperationException.class, () -> glider.alive().clear());
    }

    @Test
    void equalityCoversCellsAndGeneration() {
        Universe glider = universe(0, 0, GLIDER);

        assertEquals(glider, universe(0, 0, GLIDER));
        assertEquals(glider.hashCode(), universe(0, 0, GLIDER).hashCode());
        assertFalse(glider.equals(glider.next()));
    }
}
