package life.net;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.StringJoiner;
import life.core.Cell;
import life.net.Message.Clear;
import life.net.Message.Error;
import life.net.Message.Load;
import life.net.Message.Notice;
import life.net.Message.Patterns;
import life.net.Message.Save;
import life.net.Message.SetSpeed;
import life.net.Message.Start;
import life.net.Message.State;
import life.net.Message.Step;
import life.net.Message.Stop;
import life.net.Message.Toggle;

/**
 * Translates {@link Message}s to and from single lines of text.
 *
 * <p>The protocol is line-oriented UTF-8 over TCP, one message per line, tokens separated by
 * single spaces and the first token naming the message:
 *
 * <pre>
 * client to server   toggle &lt;x&gt; &lt;y&gt; | start | stop | step | clear
 *                    speed &lt;millis&gt; | patterns | save &lt;name&gt; | load &lt;name&gt;
 * server to client   state &lt;generation&gt; &lt;running&gt; &lt;speedMillis&gt; [&lt;x&gt;,&lt;y&gt; ...]
 *                    notice &lt;text&gt; | error &lt;text&gt;
 * </pre>
 *
 * <p>Being plain text, a game can be watched or driven with nothing but {@code telnet}, which is
 * worth more during development than a compact binary framing would be.
 */
public final class Wire {

    private Wire() {
    }

    /** The single line representing {@code message}, without a line terminator. */
    public static String encode(Message message) {
        return switch (message) {
            case Toggle(long x, long y) -> "toggle " + x + " " + y;
            case Start() -> "start";
            case Stop() -> "stop";
            case Step() -> "step";
            case Clear() -> "clear";
            case SetSpeed(int millis) -> "speed " + millis;
            case Patterns() -> "patterns";
            case Save(String name) -> "save " + name;
            case Load(String name) -> "load " + name;
            case State state -> encodeState(state);
            case Notice(String text) -> "notice " + oneLine(text);
            case Error(String text) -> "error " + oneLine(text);
        };
    }

    /**
     * The message represented by {@code line}.
     *
     * @throws ProtocolException if the command is unknown or its arguments are malformed
     */
    public static Message decode(String line) {
        String trimmed = line.strip();
        int split = trimmed.indexOf(' ');
        String command = (split < 0 ? trimmed : trimmed.substring(0, split)).toLowerCase(Locale.ROOT);
        String arguments = split < 0 ? "" : trimmed.substring(split + 1).strip();

        return switch (command) {
            case "toggle" -> {
                String[] parts = arguments(arguments, 2, "toggle <x> <y>");
                yield new Toggle(number(parts[0]), number(parts[1]));
            }
            case "start" -> new Start();
            case "stop" -> new Stop();
            case "step" -> new Step();
            case "clear" -> new Clear();
            case "speed" -> new SetSpeed(integer(arguments(arguments, 1, "speed <millis>")[0]));
            case "patterns" -> new Patterns();
            case "save" -> new Save(name(arguments, "save"));
            case "load" -> new Load(name(arguments, "load"));
            case "state" -> decodeState(arguments);
            case "notice" -> new Notice(arguments);
            case "error" -> new Error(arguments);
            default -> throw new ProtocolException("unknown command '" + command + "'");
        };
    }

    private static String oneLine(String text) {
        return text.replaceAll("\\s+", " ").strip();
    }

    private static String encodeState(State state) {
        StringJoiner out = new StringJoiner(" ");
        out.add("state")
                .add(Long.toString(state.generation()))
                .add(Boolean.toString(state.running()))
                .add(Integer.toString(state.speedMillis()));
        state.alive().forEach(cell -> out.add(cell.toString()));
        return out.toString();
    }

    private static State decodeState(String arguments) {
        String[] parts = arguments.isEmpty() ? new String[0] : arguments.split(" +");
        if (parts.length < 3) {
            throw new ProtocolException(
                    "expected 'state <generation> <running> <speedMillis> [<x>,<y> ...]'");
        }
        long generation = number(parts[0]);
        boolean running = flag(parts[1]);
        int speedMillis = integer(parts[2]);

        Set<Cell> alive = new HashSet<>(parts.length - 3);
        for (int i = 3; i < parts.length; i++) {
            alive.add(cell(parts[i]));
        }
        return new State(generation, running, speedMillis, alive);
    }

    private static Cell cell(String token) {
        int comma = token.indexOf(',');
        if (comma < 0) {
            throw new ProtocolException("expected a cell as '<x>,<y>' but got '" + token + "'");
        }
        return new Cell(number(token.substring(0, comma)), number(token.substring(comma + 1)));
    }

    private static String[] arguments(String arguments, int expected, String usage) {
        String[] parts = arguments.isEmpty() ? new String[0] : arguments.split(" +");
        if (parts.length != expected) {
            throw new ProtocolException("expected '" + usage + "'");
        }
        return parts;
    }

    private static String name(String arguments, String command) {
        String name = arguments(arguments, 1, command + " <name>")[0];
        if (!Message.VALID_NAME.matcher(name).matches()) {
            throw new ProtocolException(
                    "'" + name + "' is not a valid pattern name (" + Message.VALID_NAME.pattern() + ")");
        }
        return name;
    }

    private static long number(String token) {
        try {
            return Long.parseLong(token);
        } catch (NumberFormatException e) {
            throw new ProtocolException("'" + token + "' is not a number");
        }
    }

    private static int integer(String token) {
        long value = number(token);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new ProtocolException("'" + token + "' is out of range");
        }
        return (int) value;
    }

    private static boolean flag(String token) {
        return switch (token) {
            case "true" -> true;
            case "false" -> false;
            default -> throw new ProtocolException("'" + token + "' is not true or false");
        };
    }
}
