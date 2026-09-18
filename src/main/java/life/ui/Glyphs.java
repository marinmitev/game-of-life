package life.ui;

/**
 * The characters used to draw cells.
 *
 * <p>Each character carries two vertically adjacent cells, which doubles the number of rows that
 * fit on screen: a 100 x 100 universe needs 100 columns and only 50 terminal lines. {@link #ASCII}
 * is the fallback for terminals that cannot show the box-drawing characters.
 */
public enum Glyphs {

    /** Half-block characters; requires a UTF-8 terminal. */
    BLOCKS('\u2588', '\u2580', '\u2584', ' '),

    /** Plain ASCII approximations of the same four states. */
    ASCII('#', '"', '_', ' ');

    private final char both;
    private final char upper;
    private final char lower;
    private final char neither;

    Glyphs(char both, char upper, char lower, char neither) {
        this.both = both;
        this.upper = upper;
        this.lower = lower;
        this.neither = neither;
    }

    /** The character showing a pair of stacked cells. */
    public char of(boolean upperAlive, boolean lowerAlive) {
        if (upperAlive) {
            return lowerAlive ? both : upper;
        }
        return lowerAlive ? lower : neither;
    }
}
