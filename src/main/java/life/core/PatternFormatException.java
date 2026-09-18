package life.core;

/** Thrown when a pattern file cannot be parsed, or when a universe cannot be written as one. */
public class PatternFormatException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public PatternFormatException(String message) {
        super(message);
    }

    static PatternFormatException atLine(int lineNumber, String message) {
        return new PatternFormatException("line " + lineNumber + ": " + message);
    }
}
