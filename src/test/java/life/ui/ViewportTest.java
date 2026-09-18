package life.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import life.core.Cell;
import org.junit.jupiter.api.Test;

class ViewportTest {

    @Test
    void centresOnACell() {
        Viewport viewport = Viewport.centredOn(0, 0, 100, 100);

        assertEquals(-50, viewport.left());
        assertEquals(-50, viewport.top());
        assertTrue(viewport.contains(new Cell(-50, -50)));
        assertTrue(viewport.contains(new Cell(49, 49)));
        assertFalse(viewport.contains(new Cell(50, 0)));
        assertFalse(viewport.contains(new Cell(0, -51)));
    }

    @Test
    void mapsCellsToScreenPositionsAndBack() {
        Viewport viewport = new Viewport(10, 20, 8, 4);

        assertEquals(0, viewport.columnOf(new Cell(10, 20)));
        assertEquals(0, viewport.rowOf(new Cell(10, 20)));
        assertEquals(7, viewport.columnOf(new Cell(17, 23)));
        assertEquals(3, viewport.rowOf(new Cell(17, 23)));
        assertEquals(new Cell(13, 22), viewport.cellAt(3, 2));
    }

    @Test
    void panningMovesTheWindowNotTheSize() {
        Viewport panned = new Viewport(0, 0, 10, 10).pan(-3, 5);

        assertEquals(new Viewport(-3, 5, 10, 10), panned);
    }

    @Test
    void resizingKeepsTheCentre() {
        Viewport resized = Viewport.centredOn(1000, 2000, 100, 100).resized(20, 10);

        assertEquals(Viewport.centredOn(1000, 2000, 20, 10), resized);
        assertTrue(resized.contains(new Cell(1000, 2000)));
    }

    @Test
    void followingAVisibleCursorChangesNothing() {
        Viewport viewport = new Viewport(0, 0, 10, 10);

        assertEquals(viewport, viewport.following(new Cell(5, 5)));
        assertEquals(viewport, viewport.following(new Cell(0, 0)));
        assertEquals(viewport, viewport.following(new Cell(9, 9)));
    }

    @Test
    void followingScrollsByTheSmallestStepAtEachEdge() {
        Viewport viewport = new Viewport(0, 0, 10, 10);

        assertEquals(new Viewport(1, 0, 10, 10), viewport.following(new Cell(10, 5)));
        assertEquals(new Viewport(-1, 0, 10, 10), viewport.following(new Cell(-1, 5)));
        assertEquals(new Viewport(0, 1, 10, 10), viewport.following(new Cell(5, 10)));
        assertEquals(new Viewport(0, -1, 10, 10), viewport.following(new Cell(5, -1)));
    }

    @Test
    void followingAFarAwayCursorPutsItAtTheEdge() {
        Viewport viewport = new Viewport(0, 0, 10, 10).following(new Cell(1000, -1000));

        assertTrue(viewport.contains(new Cell(1000, -1000)));
        assertEquals(new Viewport(991, -1000, 10, 10), viewport);
    }

    @Test
    void worksUnchangedAcrossTheWrapSeam() {
        Viewport viewport = Viewport.centredOn(Long.MAX_VALUE, Long.MAX_VALUE, 10, 10);

        assertTrue(viewport.contains(new Cell(Long.MAX_VALUE, Long.MAX_VALUE)));
        assertTrue(viewport.contains(new Cell(Long.MIN_VALUE, Long.MIN_VALUE)),
                "the far corner of the universe is inside a viewport straddling the seam");
        assertEquals(5, viewport.columnOf(new Cell(Long.MAX_VALUE, Long.MAX_VALUE)));
        assertEquals(5, viewport.rowOf(new Cell(Long.MAX_VALUE, Long.MAX_VALUE)));
        assertFalse(viewport.contains(new Cell(0, 0)));
    }

    @Test
    void panningWrapsAroundTheUniverse() {
        Viewport atTheEdge = new Viewport(Long.MAX_VALUE, 0, 4, 4);

        Viewport wrapped = atTheEdge.pan(1, 0);

        assertEquals(Long.MIN_VALUE, wrapped.left());
        assertTrue(wrapped.contains(new Cell(Long.MIN_VALUE, 0)));
    }

    @Test
    void followingWrapsAroundTheUniverse() {
        Viewport atTheEdge = new Viewport(Long.MAX_VALUE - 3, 0, 4, 4);

        Viewport followed = atTheEdge.following(new Cell(Long.MIN_VALUE, 0));

        assertTrue(followed.contains(new Cell(Long.MIN_VALUE, 0)));
        assertEquals(Long.MAX_VALUE - 2, followed.left());
    }

    @Test
    void rejectsAnEmptyViewport() {
        assertThrows(IllegalArgumentException.class, () -> new Viewport(0, 0, 0, 10));
        assertThrows(IllegalArgumentException.class, () -> new Viewport(0, 0, 10, -1));
    }
}
