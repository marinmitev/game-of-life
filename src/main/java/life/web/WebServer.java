package life.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import life.net.GameHub;
import life.net.Message;
import life.net.ProtocolException;
import life.net.Wire;

/**
 * Serves a {@link GameHub} to web browsers: a canvas client, and the same line protocol the
 * terminal clients use.
 *
 * <p>A browser cannot open a raw socket, so the protocol is carried over HTTP instead of being
 * replaced. Updates go out on a Server-Sent Events stream, where each message is one
 * {@code data:} line of exactly the text a {@code telnet} session would see, and commands come
 * back one per {@code POST}. That is enough for this game — the traffic is a continuous stream
 * outwards and the occasional keystroke inwards — and it needs no framing code and no dependency
 * beyond the JDK.
 */
public final class WebServer implements AutoCloseable {

    /** Default port, used when none is given on the command line. */
    public static final int DEFAULT_PORT = 8080;

    /** How often a comment is sent to keep an idle stream open and notice dead tabs. */
    private static final int HEARTBEAT_SECONDS = 15;

    private static final String ASSETS = "/web/";

    private static final Map<String, String> ASSET_ROUTES = Map.of(
            "/", "index.html",
            "/index.html", "index.html",
            "/app.js", "app.js",
            "/style.css", "style.css");

    private static final Map<String, String> CONTENT_TYPES = Map.of(
            "html", "text/html; charset=utf-8",
            "js", "text/javascript; charset=utf-8",
            "css", "text/css; charset=utf-8");

    private final GameHub hub;
    private final HttpServer http;
    private final Consumer<String> log;
    private final ExecutorService requests = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService heartbeats =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "life-web-heartbeat");
                thread.setDaemon(true);
                return thread;
            });

    private volatile boolean closed;

    private WebServer(GameHub hub, HttpServer http, Consumer<String> log) {
        this.hub = hub;
        this.http = http;
        this.log = log;
    }

    /**
     * Binds {@code port} and starts serving {@code hub}. Port 0 picks any free port, which is what
     * the tests use; the chosen port is available from {@link #port()}.
     */
    public static WebServer start(GameHub hub, int port, Consumer<String> log) throws IOException {
        HttpServer http = HttpServer.create(new InetSocketAddress(port), 0);
        WebServer server = new WebServer(hub, http, log);

        // A stream holds its request thread for as long as the tab is open, so every request runs
        // on a virtual thread and the number of open tabs is not limited by a pool size.
        http.setExecutor(server.requests);
        http.createContext("/events", server::events);
        http.createContext("/command", server::command);
        http.createContext("/", server::asset);
        http.start();

        log.accept("serving the browser client at http://localhost:" + server.port());
        return server;
    }

    /** The port actually bound. */
    public int port() {
        return http.getAddress().getPort();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        heartbeats.shutdownNow();
        http.stop(0);
        requests.shutdownNow();
    }

    /** {@code GET /events}: subscribe to the game and stream every change as it happens. */
    private void events(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            respond(exchange, 405, "text/plain; charset=utf-8", "only GET is allowed".getBytes(StandardCharsets.UTF_8));
            return;
        }

        exchange.getResponseHeaders().add("Content-Type", "text/event-stream; charset=utf-8");
        exchange.getResponseHeaders().add("Cache-Control", "no-store");
        exchange.sendResponseHeaders(200, 0);

        OutputStream body = exchange.getResponseBody();
        EventStream stream = new EventStream(body);
        try (GameHub.Subscription subscription = hub.subscribe(stream::event)) {
            var beat = heartbeats.scheduleAtFixedRate(
                    () -> stream.comment("ping"), HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS);
            try {
                // The subscription writes from its own thread; this one only has to stay alive
                // until the browser goes away, which the heartbeat or the next update will notice.
                stream.awaitClose();
            } finally {
                beat.cancel(false);
            }
        } finally {
            exchange.close();
        }
    }

    /**
     * {@code POST /command}: one protocol line in the body. Success that changed the shared state
     * needs no reply, because the change is already on its way down every event stream.
     */
    private void command(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            respond(exchange, 405, "text/plain; charset=utf-8", "only POST is allowed".getBytes(StandardCharsets.UTF_8));
            return;
        }

        String line;
        try (InputStream in = exchange.getRequestBody()) {
            line = new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
        }

        try {
            Message reply = hub.submit(Wire.decode(line));
            if (reply instanceof Message.State) {
                respond(exchange, 204, null, new byte[0]);
            } else {
                respond(exchange, 200, "text/plain; charset=utf-8", text(reply));
            }
        } catch (ProtocolException | IllegalArgumentException e) {
            respond(exchange, 400, "text/plain; charset=utf-8",
                    String.valueOf(e.getMessage()).getBytes(StandardCharsets.UTF_8));
        }
    }

    /** Everything else: the page and its two files, read from the classpath. */
    private void asset(HttpExchange exchange) throws IOException {
        String name = ASSET_ROUTES.get(exchange.getRequestURI().getPath());
        if (name == null) {
            respond(exchange, 404, "text/plain; charset=utf-8", "not found".getBytes(StandardCharsets.UTF_8));
            return;
        }
        try (InputStream in = WebServer.class.getResourceAsStream(ASSETS + name)) {
            if (in == null) {
                log.accept("missing packaged asset " + ASSETS + name);
                respond(exchange, 500, "text/plain; charset=utf-8", "missing asset".getBytes(StandardCharsets.UTF_8));
                return;
            }
            String extension = name.substring(name.lastIndexOf('.') + 1);
            respond(exchange, 200, CONTENT_TYPES.get(extension), in.readAllBytes());
        }
    }

    private static byte[] text(Message reply) {
        String encoded = Wire.encode(reply);
        return encoded.getBytes(StandardCharsets.UTF_8);
    }

    private static void respond(HttpExchange exchange, int status, String contentType, byte[] body)
            throws IOException {
        if (contentType != null) {
            exchange.getResponseHeaders().add("Content-Type", contentType);
        }
        exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        } else {
            exchange.close();
        }
    }

    /**
     * One browser's event stream. Writes are serialised because updates arrive on the hub's
     * subscriber thread while heartbeats come from the scheduler, and a failed write means the tab
     * has gone: the exception is what tells the hub to drop the subscription.
     */
    private static final class EventStream {

        private final OutputStream body;
        private final Object closed = new Object();
        private volatile boolean open = true;

        private EventStream(OutputStream body) {
            this.body = body;
        }

        private void event(String line) {
            write("data: " + line + "\n\n");
        }

        private void comment(String text) {
            write(": " + text + "\n\n");
        }

        private void write(String frame) {
            if (!open) {
                throw new UncheckedIOException(new IOException("stream closed"));
            }
            try {
                synchronized (body) {
                    body.write(frame.getBytes(StandardCharsets.UTF_8));
                    body.flush();
                }
            } catch (IOException e) {
                markClosed();
                throw new UncheckedIOException(e);
            }
        }

        private void awaitClose() {
            synchronized (closed) {
                while (open) {
                    try {
                        closed.wait();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }

        private void markClosed() {
            open = false;
            synchronized (closed) {
                closed.notifyAll();
            }
        }
    }
}
