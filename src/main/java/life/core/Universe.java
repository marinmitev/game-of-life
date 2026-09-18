package life.core;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * An immutable generation of the Game of Life universe, stored as the sparse set of live cells.
 *
 * <p>Only live cells are represented, so memory and the cost of {@link #next()} scale with the
 * population rather than with the size of the universe. That is what makes a 2<sup>64</sup> x
 * 2<sup>64</sup> universe practical: a Gosper glider gun costs the same here as it would on a
 * 100 x 100 board.
 *
 * <p>Instances are values: every mutator returns a new universe and this class contains no
 * threads, sockets or files, which keeps the rules of the game independently testable.
 */
public final class Universe {

    private static final Universe EMPTY = new Universe(Set.of(), 0);

    private final Set<Cell> alive;
    private final long generation;

    private Universe(Set<Cell> alive, long generation) {
        this.alive = alive;
        this.generation = generation;
    }

    /** The empty universe at generation 0. */
    public static Universe empty() {
        return EMPTY;
    }

    /** A universe at generation 0 containing exactly {@code alive}. */
    public static Universe of(Set<Cell> alive) {
        return of(alive, 0);
    }

    /** A universe at {@code generation} containing exactly {@code alive}. */
    public static Universe of(Set<Cell> alive, long generation) {
        return alive.isEmpty() && generation == 0 ? EMPTY : new Universe(Set.copyOf(alive), generation);
    }

    /** The live cells, unmodifiable. */
    public Set<Cell> alive() {
        return alive;
    }

    /** How many generations have been computed since this universe's seed. */
    public long generation() {
        return generation;
    }

    public int population() {
        return alive.size();
    }

    public boolean isAlive(Cell cell) {
        return alive.contains(cell);
    }

    /**
     * Applies the B3/S23 rules once: a dead cell with exactly three live neighbours is born, a
     * live cell with two or three live neighbours survives, every other cell is dead.
     *
     * <p>Only cells adjacent to a live cell can be born, and only live cells can survive, so
     * counting neighbours of the live set alone is sufficient.
     */
    public Universe next() {
        Map<Cell, Integer> neighbourCounts = new HashMap<>(alive.size() * 4);
        for (Cell cell : alive) {
            cell.neighbours().forEach(n -> neighbourCounts.merge(n, 1, Integer::sum));
        }

        Set<Cell> survivors = new HashSet<>(neighbourCounts.size());
        neighbourCounts.forEach((cell, count) -> {
            if (count == 3 || (count == 2 && alive.contains(cell))) {
                survivors.add(cell);
            }
        });
        return new Universe(Set.copyOf(survivors), generation + 1);
    }

    /** This universe with {@code cell} alive; the generation counter is unchanged by edits. */
    public Universe with(Cell cell) {
        if (alive.contains(cell)) {
            return this;
        }
        Set<Cell> next = new HashSet<>(alive);
        next.add(cell);
        return new Universe(Set.copyOf(next), generation);
    }

    /** This universe with {@code cell} dead; the generation counter is unchanged by edits. */
    public Universe without(Cell cell) {
        if (!alive.contains(cell)) {
            return this;
        }
        Set<Cell> next = new HashSet<>(alive);
        next.remove(cell);
        return new Universe(Set.copyOf(next), generation);
    }

    /** This universe with {@code cell}'s state flipped. */
    public Universe toggle(Cell cell) {
        return alive.contains(cell) ? without(cell) : with(cell);
    }

    /** Two universes are equal when the same cells are alive in the same generation. */
    @Override
    public boolean equals(Object o) {
        return o instanceof Universe other
                && generation == other.generation
                && alive.equals(other.alive);
    }

    @Override
    public int hashCode() {
        return alive.hashCode() * 31 + Long.hashCode(generation);
    }

    @Override
    public String toString() {
        return "Universe[generation=" + generation + ", population=" + alive.size() + "]";
    }
}
