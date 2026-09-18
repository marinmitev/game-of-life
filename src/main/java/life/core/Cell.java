package life.core;

import java.util.stream.Stream;

/**
 * A single cell position in the universe.
 *
 * <p>Coordinates are {@code long}, which gives the required 2<sup>64</sup> x 2<sup>64</sup>
 * universe. The torus wrap-around is not implemented anywhere: Java {@code long} arithmetic is
 * two's-complement and silently wraps modulo 2<sup>64</sup>, so the neighbour to the right of
 * {@link Long#MAX_VALUE} is {@link Long#MIN_VALUE} by construction. Every coordinate is therefore
 * a valid position and no bounds check or modulo operation is ever needed.
 */
public record Cell(long x, long y) {

    /** The eight cells of this cell's Moore neighbourhood, in reading order. */
    public Stream<Cell> neighbours() {
        return Stream.of(
                new Cell(x - 1, y - 1), new Cell(x, y - 1), new Cell(x + 1, y - 1),
                new Cell(x - 1, y), /*                   */ new Cell(x + 1, y),
                new Cell(x - 1, y + 1), new Cell(x, y + 1), new Cell(x + 1, y + 1));
    }

    /** Renders as {@code x,y}, the form used by the wire protocol. */
    @Override
    public String toString() {
        return x + "," + y;
    }
}
