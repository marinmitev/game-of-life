package life.net;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Runs one game and serves any number of clients over TCP.
 *
 * <p>There is a single {@link Game}, so all clients see the same universe and any of them may edit
 * it or start and stop it; every change is broadcast to everyone immediately. Each connection gets
 * two virtual threads, one reading commands and one draining a small outbound queue, so a client
 * that stops reading is disconnected instead of stalling the simulation or the other clients.
 */
public final class GameServer implements AutoCloseable {

    /** Default port, used when none is given on the command line. */
    public static final int DEFAULT_PORT = 7777;

    /** Lines buffered per client before it is considered too slow to keep up. */
    private static final int OUTBOX_CAPACITY = 64;

    private final Game game;
    private final ServerSocket serverSocket;
    private final Set<Session> sessions = ConcurrentHashMap.newKeySet();
    private final ExecutorService connections = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService ticker =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "life-ticker");
                thread.setDaemon(true);
                return thread;
            });
    private final Consumer<String> log;

    private final Object tickLock = new Object();
    private ScheduledFuture<?> tick;
    private boolean tickRunning;
    private int tickSpeedMillis;

    private volatile boolean closed;

    private GameServer(Game game, ServerSocket serverSocket, Consumer<String> log) {
        this.game = game;
        this.serverSocket = serverSocket;
        this.log = log;
    }

    /**
     * Binds {@code port} and starts serving. Port 0 picks any free port, which is what the tests
     * use; the chosen port is available from {@link #port()}.
     */
    public static GameServer start(int port, Path patternsDirectory, Consumer<String> log)
            throws IOException {
        GameServer server = new GameServer(new Game(patternsDirectory), new ServerSocket(port), log);
        server.connections.submit(server::acceptLoop);
        return server;
    }

    /** The port actually bound. */
    public int port() {
        return serverSocket.getLocalPort();
    }

    /** The shared game, so an embedding process can inspect or seed it. */
    public Game game() {
        return game;
    }

    /** How many clients are currently connected. */
    public int clientCount() {
        return sessions.size();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        synchronized (tickLock) {
            if (tick != null) {
                tick.cancel(false);
                tick = null;
            }
        }
        ticker.shutdownNow();
        try {
            serverSocket.close();
        } catch (IOException e) {
            log.accept("could not close the server socket: " + e);
        }
        Set.copyOf(sessions).forEach(Session::close);
        connections.shutdownNow();
    }

    private void acceptLoop() {
        log.accept("listening on port " + port());
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

    /** Runs one command on behalf of {@code sender} and tells whoever needs to know. */
    private void handle(Message command, Session sender) {
        Message reply = game.apply(command);
        rescheduleTick();
        if (reply instanceof Message.State state) {
            broadcast(state);
        } else {
            sender.send(reply);
        }
    }

    private void broadcast(Message message) {
        String line = Wire.encode(message);
        sessions.forEach(session -> session.send(line));
    }

    /**
     * Makes the tick schedule match the game's own running flag and speed.
     *
     * <p>Comparing against the schedule in force means this can be called after every command: it
     * does nothing unless the timing actually changed, so editing cells while the game runs does
     * not keep pushing the next generation further away.
     */
    private void rescheduleTick() {
        synchronized (tickLock) {
            boolean running = !closed && game.isRunning();
            int speedMillis = game.speedMillis();
            if (running == tickRunning && speedMillis == tickSpeedMillis) {
                return;
            }
            tickRunning = running;
            tickSpeedMillis = speedMillis;

            if (tick != null) {
                tick.cancel(false);
                tick = null;
            }
            if (running) {
                tick = ticker.scheduleAtFixedRate(
                        this::advance, speedMillis, speedMillis, TimeUnit.MILLISECONDS);
            }
        }
    }

    private void advance() {
        try {
            game.tick().ifPresent(this::broadcast);
        } catch (RuntimeException e) {
            log.accept("generation failed: " + e);
        }
    }

    /** One connected client: a socket, a reader thread and a writer thread. */
    private final class Session {

        private final Socket socket;
        private final BufferedReader in;
        private final BufferedWriter out;
        private final BlockingQueue<String> outbox = new ArrayBlockingQueue<>(OUTBOX_CAPACITY);
        private volatile Future<?> reader;
        private volatile Future<?> writer;

        private Session(Socket socket) throws IOException {
            this.socket = socket;
            socket.setTcpNoDelay(true);
            this.in = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            this.out = new BufferedWriter(
                    new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
        }

        private void start() {
            writer = connections.submit(this::writeLoop);
            reader = connections.submit(this::readLoop);
            // A client that joins a game in progress needs the current state before anything else.
            send(game.state());
        }

        private String address() {
            return socket.getRemoteSocketAddress().toString();
        }

        private void send(Message message) {
            send(Wire.encode(message));
        }

        private void send(String line) {
            if (!outbox.offer(line)) {
                log.accept("disconnecting " + address() + ": too slow to keep up");
                close();
            }
        }

        private void writeLoop() {
            try {
                while (true) {
                    String line = outbox.take();
                    out.write(line);
                    out.write('\n');
                    out.flush();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException e) {
                close();
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
                        handle(Wire.decode(line), this);
                    } catch (ProtocolException | IllegalArgumentException e) {
                        send(new Message.Error(e.getMessage()));
                    }
                }
            } catch (IOException e) {
                // The client vanished; treated the same as a clean disconnect.
            } finally {
                close();
            }
        }

        private void close() {
            if (!sessions.remove(this)) {
                return;
            }
            if (reader != null) {
                reader.cancel(true);
            }
            if (writer != null) {
                writer.cancel(true);
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
