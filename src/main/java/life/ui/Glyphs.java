package life.ui;

/**
 * The characters used to draw cells: one character per cell, so what is on screen is a direct
 * picture of the universe.
 *
 * <p>{@link #ASCII} is the fallback for terminals that cannot show the block character.
 */
public enum Glyphs {

    /** A solid block; requires a UTF-8 terminal. */
    BLOCKS('\u2588'),

    /** Plain ASCII, for terminals that cannot show the block. */
    ASCII('#');

    private static final char DEAD = ' ';

    private final char alive;

    Glyphs(char alive) {
        this.alive = alive;
    }

    /** The character showing one cell. */
    public char of(boolean isAlive) {
        return isAlive ? alive : DEAD;
    }
}
