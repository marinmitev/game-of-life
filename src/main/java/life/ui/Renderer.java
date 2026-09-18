package life.ui;

import java.util.ArrayList;
import java.util.Arrays;
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

    /** The grid as one string per row of the viewport, one character per cell. */
    public static List<String> grid(Set<Cell> alive, Viewport viewport, Glyphs glyphs) {
        char[][] canvas = new char[viewport.rows()][viewport.columns()];
        for (char[] row : canvas) {
            Arrays.fill(row, glyphs.of(false));
        }
        for (Cell cell : alive) {
            if (viewport.contains(cell)) {
                canvas[viewport.rowOf(cell)][viewport.columnOf(cell)] = glyphs.of(true);
            }
        }

        List<String> lines = new ArrayList<>(canvas.length);
        for (char[] row : canvas) {
            lines.add(new String(row));
        }
        return lines;
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
