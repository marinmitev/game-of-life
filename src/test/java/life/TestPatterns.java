package life;

import java.util.HashSet;
import java.util.Set;
import life.core.Cell;
import life.core.Universe;

/** Small patterns written as text rows, shared by the tests. */
public final class TestPatterns {

    /** Still life: never changes. */
    public static final String[] BLOCK = {
        "OO",
        "OO"
    };

    /** Oscillator of period 2. */
    public static final String[] BLINKER = {
        "OOO"
    };

    /** Spaceship: translates by (1, 1) every four generations. */
    public static final String[] GLIDER = {
        ".O.",
        "..O",
        "OOO"
    };

    private TestPatterns() {
    }

    /**
     * Live cells of {@code rows} with the top-left character placed at {@code (originX, originY)}.
     * {@code O} is alive, any other character is dead.
     */
    public static Set<Cell> cells(long originX, long originY, String... rows) {
        Set<Cell> cells = new HashSet<>();
        for (int row = 0; row < rows.length; row++) {
            for (int column = 0; column < rows[row].length(); column++) {
                if (rows[row].charAt(column) == 'O') {
                    cells.add(new Cell(originX + column, originY + row));
                }
            }
        }
        return cells;
    }

    /** A universe seeded with {@code rows} at {@code (originX, originY)}. */
    public static Universe universe(long originX, long originY, String... rows) {
        return Universe.of(cells(originX, originY, rows));
    }

    /** Advances {@code universe} by {@code generations} steps. */
    public static Universe advance(Universe universe, int generations) {
        Universe result = universe;
        for (int i = 0; i < generations; i++) {
            result = result.next();
        }
        return result;
    }

    /** The live cells of {@code universe} shifted by {@code (dx, dy)}. */
    public static Set<Cell> shifted(Universe universe, long dx, long dy) {
        Set<Cell> shifted = new HashSet<>();
        universe.alive().forEach(cell -> shifted.add(new Cell(cell.x() + dx, cell.y() + dy)));
        return shifted;
    }
}
