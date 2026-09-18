package life.net;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * A connection to a {@link GameServer}.
 *
 * <p>Commands are written straight through, which is fine because they come from a human pressing
 * keys. Incoming messages are read on a virtual thread and handed to a {@link Listener}; the caller
 * never has to poll.
 */
public final class GameClient implements AutoCloseable {

    /**
     * Receives what the server sends. Called on the client's reader thread.
     *
     * <p>Implementations must not throw: an exception from a listener is swallowed so that one bad
     * frame cannot take the connection down with it.
     */
    public interface Listener {

        /** The shared state has changed. */
        void onState(Message.State state);

        /** A command succeeded without changing the shared state, such as a save. */
        default void onNotice(Message.Notice notice) {
        }

        /** A command sent by this client could not be carried out. */
        default void onError(Message.Error error) {
        }

        /** The connection has ended, for any reason including {@link GameClient#close()}. */
        default void onDisconnect() {
        }
    }

    private final Socket socket;
    private final BufferedWriter out;
    private final Thread reader;
    private volatile boolean closed;

    private GameClient(Socket socket, Listener listener) throws IOException {
        this.socket = socket;
        socket.setTcpNoDelay(true);
        this.out = new BufferedWriter(
                new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
        BufferedReader in = new BufferedReader(
                new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        this.reader = Thread.ofVirtual()
                .name("life-client-reader")
                .unstarted(() -> readLoop(in, listener));
    }

    /** Connects to {@code host:port} and starts delivering messages to {@code listener}. */
    public static GameClient connect(String host, int port, Listener listener) throws IOException {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), 10_000);
        GameClient client = new GameClient(socket, listener);
        client.reader.start();
        return client;
    }

    /** Sends a command to the server. */
    public void send(Message command) {
        if (closed) {
            return;
        }
        try {
            synchronized (out) {
                out.write(Wire.encode(command));
                out.write('\n');
                out.flush();
            }
        } catch (IOException e) {
            close();
            throw new UncheckedIOException("could not send " + Wire.encode(command), e);
        }
    }

    /** Whether this client is still connected. */
    public boolean isOpen() {
        return !closed;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            socket.close();
        } catch (IOException e) {
            // Disconnecting anyway.
        }
    }

    private void readLoop(BufferedReader in, Listener listener) {
        try {
            String line;
            while ((line = in.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                deliver(line, listener);
            }
        } catch (IOException e) {
            // The server went away, or close() shut the socket from underneath the read.
        } finally {
            closed = true;
            safely(listener::onDisconnect);
        }
    }

    private void deliver(String line, Listener listener) {
        Message message;
        try {
            message = Wire.decode(line);
        } catch (ProtocolException e) {
            safely(() -> listener.onError(new Message.Error("unreadable line from server: " + e.getMessage())));
            return;
        }
        switch (message) {
            case Message.State state -> safely(() -> listener.onState(state));
            case Message.Notice notice -> safely(() -> listener.onNotice(notice));
            case Message.Error error -> safely(() -> listener.onError(error));
            // Commands only travel the other way; a server sending one is a bug, not a crash.
            default -> safely(() -> listener.onError(new Message.Error("unexpected '" + line + "'")));
        }
    }

    private static void safely(Runnable callback) {
        try {
            callback.run();
        } catch (RuntimeException e) {
            // Documented on Listener: a misbehaving listener must not break the connection.
        }
    }
}
