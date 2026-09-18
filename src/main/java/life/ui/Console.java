package life.ui;

import static org.jline.keymap.KeyMap.key;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import life.core.Cell;
import life.net.Game;
import life.net.GameClient;
import life.net.Message;
import org.jline.keymap.BindingReader;
import org.jline.keymap.KeyMap;
import org.jline.terminal.Attributes;
import org.jline.terminal.Size;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;
import org.jline.utils.Display;
import org.jline.utils.InfoCmp.Capability;

/**
 * The console client: a full-screen view of the shared universe that can also edit it.
 *
 * <p>The window is purely a translator. A key press becomes a {@link Message} sent to the server
 * and an incoming {@link Message.State} becomes a redraw, with no game logic in between, which is
 * why several clients can edit the same universe at the same time without coordinating.
 *
 * <p>All drawing happens on one painter thread that repaints at most every {@value #FRAME_MILLIS}
 * milliseconds, so a fast simulation cannot flood the terminal and the network thread never blocks
 * on I/O it does not own.
 *
 * <p>One cell is drawn as one character. A terminal window therefore shows as many rows of the
 * universe as it has lines, and the 100 x 100 editing area is reached by moving the cursor, which
 * scrolls the view at the edges.
 */
public final class Console implements GameClient.Listener, AutoCloseable {

    /** Shortest interval between repaints. */
    private static final int FRAME_MILLIS = 16;

    /** Lines reserved at the bottom for the status and help lines. */
    private static final int STATUS_LINES = 2;

    /** The interactively configurable area named in the requirements. */
    private static final int EDITOR_SIZE = 100;

    private static final int SPEED_STEP_MILLIS = 20;
    private static final long MESSAGE_MILLIS = 5_000;

    private enum Action {
        UP, DOWN, LEFT, RIGHT,
        PAGE_UP, PAGE_DOWN, PAGE_LEFT, PAGE_RIGHT,
        TOGGLE, RUN_OR_PAUSE, STEP, CLEAR, FASTER, SLOWER,
        GOTO, HOME, SAVE, LOAD, QUIT
    }

    private final Terminal terminal;
    private final Attributes originalAttributes;
    private final Display display;
    private final BindingReader keyReader;
    private final KeyMap<Action> keys;
    private final Glyphs glyphs;
    private final String connection;
    private final ScheduledExecutorService painter =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "life-painter");
                thread.setDaemon(true);
                return thread;
            });

    private volatile GameClient client;
    private volatile Message.State state =
            new Message.State(0, false, Game.DEFAULT_SPEED_MILLIS, Set.of());
    private volatile Cell cursor = new Cell(0, 0);
    private volatile Viewport viewport = Viewport.centredOn(0, 0, EDITOR_SIZE, EDITOR_SIZE);
    private volatile String message = "";
    private volatile long messageExpiresAt;
    private volatile String prompt = "";
    private volatile boolean dirty = true;
    private volatile boolean quit;

    private Console(Terminal terminal, String connection, boolean preferAscii) {
        this.terminal = terminal;
        this.connection = connection;
        this.originalAttributes = terminal.enterRawMode();
        this.display = new Display(terminal, true);
        this.keyReader = new BindingReader(terminal.reader());
        this.keys = keyMap(terminal);
        this.glyphs = glyphsFor(terminal.getType(), preferAscii);

        terminal.puts(Capability.enter_ca_mode);
        terminal.puts(Capability.keypad_xmit);
        terminal.flush();
    }

    /**
     * Connects to {@code host:port} and runs until the user quits or the server goes away.
     *
     * <p>The terminal is opened as UTF-8 so that the solid block can be written even where the
     * platform's default encoding is something older. Pass {@code preferAscii} for the rare
     * terminal that cannot show it.
     */
    public static void run(String host, int port, boolean preferAscii) throws IOException {
        try (Terminal terminal = TerminalBuilder.builder()
                        .system(true)
                        .nativeSignals(true)
                        .encoding(StandardCharsets.UTF_8)
                        .build();
                Console console = new Console(terminal, host + ":" + port, preferAscii)) {
            console.client = GameClient.connect(host, port, console);
            console.show("connected to " + host + ":" + port);
            console.painter.scheduleAtFixedRate(
                    console::paintIfDirty, 0, FRAME_MILLIS, TimeUnit.MILLISECONDS);
            console.readKeysUntilQuit();
        }
    }

    @Override
    public void onState(Message.State state) {
        this.state = state;
        dirty = true;
    }

    @Override
    public void onNotice(Message.Notice notice) {
        show(notice.text());
    }

    @Override
    public void onError(Message.Error error) {
        show("! " + error.text());
    }

    @Override
    public void onDisconnect() {
        show("disconnected from the server - press q to quit");
    }

    @Override
    public void close() {
        painter.shutdownNow();
        terminal.puts(Capability.exit_ca_mode);
        terminal.puts(Capability.keypad_local);
        terminal.flush();
        terminal.setAttributes(originalAttributes);
        GameClient connected = client;
        if (connected != null) {
            connected.close();
        }
    }

    private void readKeysUntilQuit() {
        while (!quit) {
            Action action = keyReader.readBinding(keys);
            if (action != null) {
                perform(action);
                dirty = true;
            }
        }
    }

    private void perform(Action action) {
        switch (action) {
            case UP -> moveCursor(0, -1);
            case DOWN -> moveCursor(0, 1);
            case LEFT -> moveCursor(-1, 0);
            case RIGHT -> moveCursor(1, 0);
            case PAGE_UP -> moveCursor(0, -viewport.rows());
            case PAGE_DOWN -> moveCursor(0, viewport.rows());
            case PAGE_LEFT -> moveCursor(-viewport.columns(), 0);
            case PAGE_RIGHT -> moveCursor(viewport.columns(), 0);
            case TOGGLE -> send(new Message.Toggle(cursor.x(), cursor.y()));
            case RUN_OR_PAUSE -> send(state.running() ? new Message.Stop() : new Message.Start());
            case STEP -> send(new Message.Step());
            case CLEAR -> send(new Message.Clear());
            case FASTER -> send(new Message.SetSpeed(state.speedMillis() - SPEED_STEP_MILLIS));
            case SLOWER -> send(new Message.SetSpeed(state.speedMillis() + SPEED_STEP_MILLIS));
            case GOTO -> goToTypedCell();
            case HOME -> moveTo(new Cell(0, 0));
            case SAVE -> sendNamed("save as", Message.Save::new);
            case LOAD -> {
                send(new Message.Patterns());
                sendNamed("load", Message.Load::new);
            }
            case QUIT -> quit = true;
        }
    }

    private void moveCursor(long dx, long dy) {
        cursor = new Cell(cursor.x() + dx, cursor.y() + dy);
        viewport = viewport.following(cursor);
    }

    private void moveTo(Cell target) {
        cursor = target;
        viewport = Viewport.centredOn(target.x(), target.y(), viewport.columns(), viewport.rows());
    }

    private void goToTypedCell() {
        String typed = readLine("go to x,y");
        if (typed == null || typed.isBlank()) {
            return;
        }
        String[] parts = typed.split("[ ,]+");
        try {
            if (parts.length != 2) {
                throw new NumberFormatException(typed);
            }
            moveTo(new Cell(Long.parseLong(parts[0]), Long.parseLong(parts[1])));
        } catch (NumberFormatException e) {
            show("! expected two coordinates, for example 9223372036854775807,0");
        }
    }

    private void sendNamed(String label, Function<String, Message> command) {
        String name = readLine(label);
        if (name == null || name.isBlank()) {
            return;
        }
        if (!Message.VALID_NAME.matcher(name).matches()) {
            show("! a name may only contain letters, digits, '.', '_' and '-'");
            return;
        }
        send(command.apply(name));
    }

    private void send(Message command) {
        GameClient connected = client;
        if (connected == null || !connected.isOpen()) {
            show("! not connected");
            return;
        }
        try {
            connected.send(command);
        } catch (RuntimeException e) {
            show("! could not send: " + e.getMessage());
        }
    }

    /**
     * Reads a line typed into the status area, or {@code null} if the user pressed escape. The
     * painter keeps drawing while this blocks, so the universe carries on updating behind the
     * prompt.
     */
    private String readLine(String label) {
        StringBuilder typed = new StringBuilder();
        try {
            while (true) {
                prompt = label + ": " + typed + "_";
                dirty = true;
                int character = keyReader.readCharacter();
                switch (character) {
                    case '\r', '\n' -> {
                        return typed.toString();
                    }
                    case 27, 3, -1 -> { // escape, Ctrl-C, end of input
                        return null;
                    }
                    case 127, 8 -> {
                        if (!typed.isEmpty()) {
                            typed.deleteCharAt(typed.length() - 1);
                        }
                    }
                    default -> {
                        if (character >= ' ') {
                            typed.append((char) character);
                        }
                    }
                }
            }
        } finally {
            prompt = "";
            dirty = true;
        }
    }

    private void show(String text) {
        message = text;
        messageExpiresAt = System.currentTimeMillis() + MESSAGE_MILLIS;
        dirty = true;
    }

    private void paintIfDirty() {
        if (!dirty) {
            return;
        }
        dirty = false;
        try {
            paint();
        } catch (RuntimeException e) {
            // A failed frame must not kill the painter; the next one will try again.
            dirty = true;
        }
    }

    private void paint() {
        Size size = terminal.getSize();
        int columns = Math.max(size.getColumns(), 1);
        int lines = Math.max(size.getRows(), STATUS_LINES + 1);
        int gridLines = lines - STATUS_LINES;

        Viewport view = viewport;
        if (view.columns() != columns || view.rows() != gridLines) {
            view = Viewport.centredOn(cursor.x(), cursor.y(), columns, gridLines);
            viewport = view;
        }

        Message.State current = state;
        Cell at = cursor;
        List<String> grid = Renderer.grid(current.alive(), view, glyphs);
        int cursorLine = view.contains(at) ? view.rowOf(at) : -1;
        int cursorColumn = view.contains(at) ? view.columnOf(at) : 0;

        List<AttributedString> screen = new ArrayList<>(lines);
        for (int line = 0; line < grid.size(); line++) {
            screen.add(line == cursorLine
                    ? highlight(grid.get(line), cursorColumn)
                    : new AttributedString(grid.get(line)));
        }
        screen.add(new AttributedString(
                truncate(Renderer.status(current, view, at, connection), columns),
                AttributedStyle.DEFAULT.inverse()));
        screen.add(new AttributedString(truncate(bottomLine(), columns)));

        display.resize(lines, columns);
        display.update(screen, cursorLine < 0 ? -1 : cursorLine * columns + cursorColumn);
    }

    private String bottomLine() {
        String typing = prompt;
        if (!typing.isEmpty()) {
            return typing;
        }
        return System.currentTimeMillis() < messageExpiresAt ? message : Renderer.HELP;
    }

    /** {@code line} with the character at {@code column} shown in reverse video. */
    private static AttributedString highlight(String line, int column) {
        AttributedStringBuilder out = new AttributedStringBuilder();
        out.append(line, 0, column);
        out.styled(AttributedStyle::inverse, String.valueOf(line.charAt(column)));
        out.append(line, column + 1, line.length());
        return out.toAttributedString();
    }

    private static String truncate(String text, int columns) {
        return text.length() <= columns ? text : text.substring(0, columns);
    }

    /**
     * The solid block unless the user asked for ASCII or the terminal is too limited to redraw a
     * screen at all, in which case drawing mojibake would only make things worse.
     *
     * <p>The terminal's own reported encoding is deliberately not consulted: on Windows it is
     * often the legacy code page even though the console renders Unicode perfectly well, and
     * {@link #run} writes UTF-8 regardless.
     */
    static Glyphs glyphsFor(String terminalType, boolean preferAscii) {
        boolean dumb = terminalType == null || terminalType.startsWith(Terminal.TYPE_DUMB);
        return preferAscii || dumb ? Glyphs.ASCII : Glyphs.BLOCKS;
    }

    private static KeyMap<Action> keyMap(Terminal terminal) {
        KeyMap<Action> keys = new KeyMap<>();
        bind(keys, Action.UP, key(terminal, Capability.key_up));
        bind(keys, Action.DOWN, key(terminal, Capability.key_down));
        bind(keys, Action.LEFT, key(terminal, Capability.key_left));
        bind(keys, Action.RIGHT, key(terminal, Capability.key_right));
        bind(keys, Action.PAGE_UP, key(terminal, Capability.key_ppage));
        bind(keys, Action.PAGE_DOWN, key(terminal, Capability.key_npage));
        bind(keys, Action.HOME, key(terminal, Capability.key_home));

        keys.bind(Action.PAGE_LEFT, "[", "{");
        keys.bind(Action.PAGE_RIGHT, "]", "}");
        keys.bind(Action.TOGGLE, " ");
        keys.bind(Action.RUN_OR_PAUSE, "\r", "\n");
        keys.bind(Action.STEP, "n", "N");
        keys.bind(Action.CLEAR, "c", "C");
        keys.bind(Action.FASTER, "+", "=");
        keys.bind(Action.SLOWER, "-", "_");
        keys.bind(Action.GOTO, "g", "G");
        keys.bind(Action.HOME, "0");
        keys.bind(Action.SAVE, "s", "S");
        keys.bind(Action.LOAD, "l", "L");
        keys.bind(Action.QUIT, "q", "Q", "\u0003");
        return keys;
    }

    private static void bind(KeyMap<Action> keys, Action action, String sequence) {
        if (sequence != null) {
            keys.bind(action, sequence);
        }
    }
}
