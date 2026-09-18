package life.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import life.core.Cell;
import life.net.Message;

/**
 * Turns a game state into the lines of text that make up the screen.
 *
 * <p>Deliberately free of terminal handling: it takes values and returns strings, so the whole
 * appearance of the client can be tested without a terminal.
 */
public final class Renderer {

    /** The key bindings, shown as the bottom line of the screen. */
    public static final String HELP =
            "arrows move  space toggle  enter run/pause  n step  c clear  "
                    + "+/- speed  g goto  s save  l load  q quit";

    private Renderer() {
    }

    /**
     * The grid as one string per terminal line. Two cell rows share a line, so the result has
     * {@code ceil(viewport.rows() / 2)} entries.
     */
    public static List<String> grid(Set<Cell> alive, Viewport viewport, Glyphs glyphs) {
        int columns = viewport.columns();
        int rows = viewport.rows();

        boolean[][] live = new boolean[rows][columns];
        for (Cell cell : alive) {
            if (viewport.contains(cell)) {
                live[viewport.rowOf(cell)][viewport.columnOf(cell)] = true;
            }
        }

        List<String> lines = new ArrayList<>((rows + 1) / 2);
        for (int row = 0; row < rows; row += 2) {
            boolean[] upper = live[row];
            boolean[] lower = row + 1 < rows ? live[row + 1] : new boolean[columns];
            char[] line = new char[columns];
            for (int column = 0; column < columns; column++) {
                line[column] = glyphs.of(upper[column], lower[column]);
            }
            lines.add(new String(line));
        }
        return lines;
    }

    /** The screen line of the character that shows {@code cell}. */
    public static int lineOf(Viewport viewport, Cell cell) {
        return viewport.rowOf(cell) / 2;
    }

    /** The one-line summary of the game and the cursor. */
    public static String status(
            Message.State state, Viewport viewport, Cell cursor, String connection) {
        return "gen %d | pop %d | %s | %dms | cursor %d,%d %s | view %d,%d %dx%d | %s".formatted(
                state.generation(),
                state.population(),
                state.running() ? "RUNNING" : "PAUSED",
                state.speedMillis(),
                cursor.x(),
                cursor.y(),
                state.alive().contains(cursor) ? "alive" : "dead",
                viewport.left(),
                viewport.top(),
                viewport.columns(),
                viewport.rows(),
                connection);
    }
}
