package life.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads and writes universes as plain text {@code .cells} files.
 *
 * <p>The format is the one used by the Life community, so the example files are readable and
 * editable by hand and interchangeable with other Life programs:
 *
 * <pre>
 * !Name: Gosper glider gun     lines starting with '!' are comments
 * !Origin: -18 -4              absolute coordinate of the top-left character (default 0 0)
 * !Generation: 0               generation counter of the saved state (default 0)
 * ........................O    'O' (also 'o' or '*') is a live cell,
 * ......................O.O    '.' (also a space) is a dead cell,
 * ............OO......OO       trailing dead cells may be omitted.
 * </pre>
 *
 * <p>The two extra headers, {@code !Origin} and {@code !Generation}, make the format lossless for
 * this program's state: a file round-trips back to an equal {@link Universe}, wherever on the
 * torus the pattern happens to live.
 */
public final class PatternFile {

    /** File extension used for pattern files, including the dot. */
    public static final String EXTENSION = ".cells";

    /**
     * Largest width or height that can be written. A universe is unbounded, but a pattern file is
     * a rectangle of characters, so saving cells spread over an enormous area is refused rather
     * than attempted.
     */
    static final long MAX_SPAN = 4096;

    private static final Pattern ORIGIN = Pattern.compile("!\\s*origin\\s*:\\s*(\\S+)\\s+(\\S+)\\s*",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern GENERATION = Pattern.compile("!\\s*generation\\s*:\\s*(\\S+)\\s*",
            Pattern.CASE_INSENSITIVE);

    private static final char ALIVE = 'O';
    private static final char DEAD = '.';

    private PatternFile() {
    }

    /** Reads a universe from {@code file}. */
    public static Universe load(Path file) throws IOException {
        return parse(Files.readString(file, StandardCharsets.UTF_8));
    }

    /** Writes {@code universe} to {@code file}, creating or truncating it. */
    public static void save(Universe universe, Path file) throws IOException {
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(file, format(universe), StandardCharsets.UTF_8);
    }

    /**
     * Parses the contents of a pattern file.
     *
     * @throws PatternFormatException if a grid line contains an unknown character or a header
     *     holds an unparsable number
     */
    public static Universe parse(String text) {
        String[] lines = text.split("\r?\n");

        long originX = 0;
        long originY = 0;
        long generation = 0;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            Matcher origin = ORIGIN.matcher(line);
            if (origin.matches()) {
                originX = parseLong(origin.group(1), i + 1, "origin x");
                originY = parseLong(origin.group(2), i + 1, "origin y");
                continue;
            }
            Matcher gen = GENERATION.matcher(line);
            if (gen.matches()) {
                generation = parseLong(gen.group(1), i + 1, "generation");
            }
        }

        Set<Cell> alive = new HashSet<>();
        long row = 0;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.startsWith("!")) {
                continue;
            }
            for (int column = 0; column < line.length(); column++) {
                char c = line.charAt(column);
                switch (c) {
                    case ALIVE, 'o', '*' -> alive.add(new Cell(originX + column, originY + row));
                    case DEAD, ' ', '\t' -> { }
                    default -> throw PatternFormatException.atLine(
                            i + 1, "unexpected character '" + c + "' at column " + (column + 1));
                }
            }
            row++;
        }
        return Universe.of(alive, generation);
    }

    /**
     * Renders {@code universe} as the contents of a pattern file.
     *
     * @throws PatternFormatException if the live cells are spread over more than {@link #MAX_SPAN}
     *     cells in either direction
     */
    public static String format(Universe universe) {
        StringBuilder out = new StringBuilder();
        if (universe.population() == 0) {
            return out.append("!Origin: 0 0\n")
                    .append("!Generation: ").append(universe.generation()).append('\n')
                    .append("!Empty universe\n")
                    .toString();
        }

        long[] horizontal = extent(universe.alive().stream().mapToLong(Cell::x).toArray(), "width");
        long[] vertical = extent(universe.alive().stream().mapToLong(Cell::y).toArray(), "height");
        long originX = horizontal[0];
        long originY = vertical[0];
        int width = (int) horizontal[1];
        int height = (int) vertical[1];

        char[][] grid = new char[height][width];
        for (char[] gridRow : grid) {
            Arrays.fill(gridRow, DEAD);
        }
        for (Cell cell : universe.alive()) {
            grid[(int) (cell.y() - originY)][(int) (cell.x() - originX)] = ALIVE;
        }

        out.append("!Origin: ").append(originX).append(' ').append(originY).append('\n');
        out.append("!Generation: ").append(universe.generation()).append('\n');
        for (char[] gridRow : grid) {
            int end = width;
            while (end > 0 && gridRow[end - 1] == DEAD) {
                end--;
            }
            out.append(gridRow, 0, end).append('\n');
        }
        return out.toString();
    }

    /**
     * The shortest interval of the circle of 2<sup>64</sup> coordinates that covers every value in
     * {@code coordinates}, as {@code {start, length}}.
     *
     * <p>On a torus there is no smallest coordinate, so the interval is found by locating the
     * largest empty gap between consecutive values and taking everything outside it. That is what
     * lets a pattern sitting on the seam between {@link Long#MAX_VALUE} and {@link Long#MIN_VALUE}
     * be written as a small rectangle instead of a universe-wide one.
     */
    private static long[] extent(long[] coordinates, String dimension) {
        long[] sorted = Arrays.stream(coordinates).distinct().sorted().toArray();
        if (sorted.length == 1) {
            return new long[] {sorted[0], 1};
        }

        long largestGap = 0;
        int afterLargestGap = 0;
        for (int i = 0; i < sorted.length; i++) {
            int next = (i + 1) % sorted.length;
            long gap = sorted[next] - sorted[i]; // wraps for the last pair, which is intended
            if (Long.compareUnsigned(gap, largestGap) > 0) {
                largestGap = gap;
                afterLargestGap = next;
            }
        }

        long length = 1 - largestGap; // 2^64 - largestGap + 1, as an unsigned value
        if (Long.compareUnsigned(length, MAX_SPAN) > 0) {
            throw new PatternFormatException(
                    "live cells span " + Long.toUnsignedString(length) + " cells in " + dimension
                            + ", which exceeds the " + MAX_SPAN + " writable to a pattern file");
        }
        return new long[] {sorted[afterLargestGap], length};
    }

    private static long parseLong(String token, int lineNumber, String what) {
        try {
            return Long.parseLong(token);
        } catch (NumberFormatException e) {
            throw PatternFormatException.atLine(lineNumber, "invalid " + what + " '" + token + "'");
        }
    }
}
