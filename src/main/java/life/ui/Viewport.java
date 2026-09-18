package life.ui;

import life.core.Cell;

/**
 * The rectangle of the universe currently on screen: the cell at {@code (left, top)} is drawn in
 * the top-left corner.
 *
 * <p>All arithmetic here is deliberately wrapping, which is what makes panning across the seam of
 * the 2<sup>64</sup> x 2<sup>64</sup> torus work without a single special case. {@code contains}
 * relies on it: the offset of a cell from the left edge is computed as a wrapping subtraction and
 * then compared as an <em>unsigned</em> number, so a viewport spanning {@link Long#MAX_VALUE} and
 * {@link Long#MIN_VALUE} behaves exactly like one in the middle of the universe.
 *
 * @param left absolute x coordinate of the leftmost column
 * @param top absolute y coordinate of the topmost row
 * @param columns visible cells across, at least one
 * @param rows visible cells down, at least one
 */
public record Viewport(long left, long top, int columns, int rows) {

    public Viewport {
        if (columns < 1 || rows < 1) {
            throw new IllegalArgumentException(
                    "viewport must be at least 1x1 but was " + columns + "x" + rows);
        }
    }

    /** A viewport of {@code columns} x {@code rows} cells with {@code (x, y)} in the middle. */
    public static Viewport centredOn(long x, long y, int columns, int rows) {
        return new Viewport(x - columns / 2, y - rows / 2, columns, rows);
    }

    public boolean contains(Cell cell) {
        return Long.compareUnsigned(cell.x() - left, columns) < 0
                && Long.compareUnsigned(cell.y() - top, rows) < 0;
    }

    /** The screen column of {@code cell}, which must be {@link #contains contained}. */
    public int columnOf(Cell cell) {
        return (int) (cell.x() - left);
    }

    /** The screen row of {@code cell}, which must be {@link #contains contained}. */
    public int rowOf(Cell cell) {
        return (int) (cell.y() - top);
    }

    /** The cell shown at screen position {@code (column, row)}. */
    public Cell cellAt(int column, int row) {
        return new Cell(left + column, top + row);
    }

    /** This viewport moved by {@code (dx, dy)} cells. */
    public Viewport pan(long dx, long dy) {
        return new Viewport(left + dx, top + dy, columns, rows);
    }

    /** This viewport at a new size, keeping its centre. */
    public Viewport resized(int columns, int rows) {
        return centredOn(left + this.columns / 2, top + this.rows / 2, columns, rows);
    }

    /**
     * This viewport, scrolled by the smallest amount that brings {@code cursor} into view. A cursor
     * that is already visible leaves the viewport untouched, so the display only moves at the edges.
     */
    public Viewport following(Cell cursor) {
        return new Viewport(
                edgeFollowing(cursor.x(), left, columns),
                edgeFollowing(cursor.y(), top, rows),
                columns,
                rows);
    }

    private static long edgeFollowing(long position, long edge, int size) {
        long offset = position - edge;
        if (Long.compareUnsigned(offset, size) < 0) {
            return edge; // already visible
        }
        // A negative signed offset means the cursor stepped off the near edge, not the far one.
        return offset < 0 ? position : position - size + 1;
    }
}
