package life.net;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;

/**
 * Serves a {@link GameHub} over TCP: the line protocol described in {@link Wire}.
 *
 * <p>All this layer does is turn a socket into a subscription and a stream of commands. The game,
 * the clock and the fan-out belong to the hub, which is what lets a browser client share the same
 * universe through {@code life.web.WebServer}.
 */
public final class GameServer implements AutoCloseable {

    /** Default port, used when none is given on the command line. */
    public static final int DEFAULT_PORT = 7777;

    private final GameHub hub;
    private final boolean ownsHub;
    private final ServerSocket serverSocket;
    private final Set<Session> sessions = ConcurrentHashMap.newKeySet();
    private final ExecutorService connections = Executors.newVirtualThreadPerTaskExecutor();
    private final Consumer<String> log;

    private volatile boolean closed;

    private GameServer(GameHub hub, boolean ownsHub, ServerSocket socket, Consumer<String> log) {
        this.hub = hub;
        this.ownsHub = ownsHub;
        this.serverSocket = socket;
        this.log = log;
    }

    /**
     * Binds {@code port} and serves a game of its own. Port 0 picks any free port, which is what
     * the tests use; the chosen port is available from {@link #port()}.
     */
    public static GameServer start(int port, Path patternsDirectory, Consumer<String> log)
            throws IOException {
        return start(new GameHub(patternsDirectory, log), true, port, log);
    }

    /** Binds {@code port} and serves {@code hub}, which the caller keeps ownership of. */
    public static GameServer start(GameHub hub, int port, Consumer<String> log) throws IOException {
        return start(hub, false, port, log);
    }

    private static GameServer start(GameHub hub, boolean ownsHub, int port, Consumer<String> log)
            throws IOException {
        GameServer server = new GameServer(hub, ownsHub, new ServerSocket(port), log);
        server.connections.submit(server::acceptLoop);
        return server;
    }

    /** The port actually bound. */
    public int port() {
        return serverSocket.getLocalPort();
    }

    /** The shared game, so an embedding process can inspect or seed it. */
    public Game game() {
        return hub.game();
    }

    /** How many TCP clients are currently connected. */
    public int clientCount() {
        return sessions.size();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            serverSocket.close();
        } catch (IOException e) {
            log.accept("could not close the server socket: " + e);
        }
        Set.copyOf(sessions).forEach(Session::close);
        connections.shutdownNow();
        if (ownsHub) {
            hub.close();
        }
    }

    private void acceptLoop() {
        log.accept("listening for terminal clients on port " + port());
        while (!closed) {
            try {
                Session session = new Session(serverSocket.accept());
                sessions.add(session);
                session.start();
                log.accept("client connected from " + session.address()
                        + " (" + sessions.size() + " connected)");
            } catch (IOException e) {
                if (!closed) {
                    log.accept("could not accept a connection: " + e);
                }
            }
        }
    }

    /** One connected client: a socket, a subscription, and the thread reading its commands. */
    private final class Session {

        private final Socket socket;
        private final BufferedReader in;
        private final BufferedWriter out;
        private volatile GameHub.Subscription subscription;
        private volatile Future<?> reader;

        private Session(Socket socket) throws IOException {
            this.socket = socket;
            socket.setTcpNoDelay(true);
            this.in = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            this.out = new BufferedWriter(
                    new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
        }

        private void start() {
            subscription = hub.subscribe(this::write);
            reader = connections.submit(this::readLoop);
        }

        private String address() {
            return socket.getRemoteSocketAddress().toString();
        }

        /**
         * Writes one line, throwing to tell the hub that this client has gone. Synchronized
         * because broadcasts arrive on the hub's thread while error replies are written on this
         * session's reader thread.
         */
        private void write(String line) {
            try {
                synchronized (out) {
                    out.write(line);
                    out.write('\n');
                    out.flush();
                }
            } catch (IOException e) {
                close();
                throw new UncheckedIOException(e);
            }
        }

        private void readLoop() {
            try {
                String line;
                while ((line = in.readLine()) != null) {
                    if (line.isBlank()) {
                        continue;
                    }
                    try {
                        Message reply = hub.submit(Wire.decode(line));
                        if (!(reply instanceof Message.State)) {
                            write(Wire.encode(reply));
                        }
                    } catch (ProtocolException | IllegalArgumentException e) {
                        write(Wire.encode(new Message.Error(e.getMessage())));
                    }
                }
            } catch (IOException | UncheckedIOException e) {
                // The client vanished; treated the same as a clean disconnect.
            } finally {
                close();
            }
        }

        private void close() {
            if (!sessions.remove(this)) {
                return;
            }
            GameHub.Subscription subscribed = subscription;
            if (subscribed != null) {
                subscribed.close();
            }
            Future<?> reading = reader;
            if (reading != null) {
                reading.cancel(true);
            }
            try {
                socket.close();
            } catch (IOException e) {
                // Nothing useful left to do with a socket that will not close.
            }
            log.accept("client " + address() + " disconnected (" + sessions.size() + " connected)");
        }
    }
}
