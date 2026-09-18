package life.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class CellTest {

    @Test
    void hasEightDistinctNeighboursNotIncludingItself() {
        Cell cell = new Cell(3, 7);
        Set<Cell> neighbours = cell.neighbours().collect(Collectors.toSet());

        assertEquals(8, neighbours.size());
        assertFalse(neighbours.contains(cell));
        assertTrue(neighbours.contains(new Cell(2, 6)));
        assertTrue(neighbours.contains(new Cell(4, 8)));
    }

    @Test
    void neighboursWrapAroundTheFarCornerOfTheUniverse() {
        Set<Cell> neighbours =
                new Cell(Long.MAX_VALUE, Long.MAX_VALUE).neighbours().collect(Collectors.toSet());

        assertTrue(neighbours.contains(new Cell(Long.MIN_VALUE, Long.MIN_VALUE)));
        assertTrue(neighbours.contains(new Cell(Long.MAX_VALUE, Long.MIN_VALUE)));
        assertTrue(neighbours.contains(new Cell(Long.MIN_VALUE, Long.MAX_VALUE)));
        assertEquals(8, neighbours.size());
    }

    @Test
    void neighboursWrapAroundTheNearCornerOfTheUniverse() {
        Set<Cell> neighbours =
                new Cell(Long.MIN_VALUE, Long.MIN_VALUE).neighbours().collect(Collectors.toSet());

        assertTrue(neighbours.contains(new Cell(Long.MAX_VALUE, Long.MAX_VALUE)));
        assertEquals(8, neighbours.size());
    }

    @Test
    void rendersAsCommaSeparatedCoordinates() {
        assertEquals("-3,9", new Cell(-3, 9).toString());
    }
}
