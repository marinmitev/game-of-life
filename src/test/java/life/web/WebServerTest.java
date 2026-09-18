package life.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import life.core.Cell;
import life.net.GameClient;
import life.net.GameHub;
import life.net.GameServer;
import life.net.Message;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** The browser front end, driven over real HTTP. */
@Timeout(60)
class WebServerTest {

    @TempDir
    Path patterns;

    private final List<String> log = new CopyOnWriteArrayList<>();
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private GameHub hub;
    private WebServer server;

    @BeforeEach
    void startServer() throws IOException {
        hub = new GameHub(patterns, log::add);
        server = WebServer.start(hub, 0, log::add);
    }

    @AfterEach
    void stopServer() {
        server.close();
        hub.close();
    }

    @Test
    void servesThePageAndItsAssets() throws Exception {
        HttpResponse<String> page = get("/");
        assertEquals(200, page.statusCode());
        assertTrue(page.body().contains("<canvas id=\"universe\">"), page.body());
        assertEquals("text/html; charset=utf-8", page.headers().firstValue("content-type").orElseThrow());

        assertEquals(200, get("/app.js").statusCode());
        assertEquals(200, get("/style.css").statusCode());
        assertTrue(get("/app.js").body().contains("BigInt"), "coordinates must not be JS numbers");
    }

    @Test
    void unknownPathsAreNotFound() throws Exception {
        assertEquals(404, get("/nope").statusCode());
    }

    @Test
    void aPostedCommandChangesTheSharedGame() throws Exception {
        HttpResponse<String> response = post("toggle 3 4");

        assertEquals(204, response.statusCode(), "a broadcast change needs no reply body");
        assertEquals(1, hub.game().state().population());
        assertTrue(hub.game().state().alive().contains(new Cell(3, 4)));
    }

    @Test
    void aCommandWithNothingToBroadcastRepliesToTheSender() throws Exception {
        post("toggle 0 0");
        post("save from-the-browser");

        HttpResponse<String> response = post("patterns");

        assertEquals(200, response.statusCode());
        assertTrue(response.body().startsWith("notice "), response.body());
        assertTrue(response.body().contains("from-the-browser"), response.body());
    }

    @Test
    void aFailedCommandExplainsItself() throws Exception {
        HttpResponse<String> response = post("load nothing-here");

        assertEquals(200, response.statusCode());
        assertEquals("error no pattern named 'nothing-here'", response.body().strip());
    }

    @Test
    void anUnreadableCommandIsRejected() throws Exception {
        HttpResponse<String> response = post("fly to the moon");

        assertEquals(400, response.statusCode());
        assertTrue(response.body().contains("unknown command"), response.body());
    }

    @Test
    void wrongMethodsAreRefused() throws Exception {
        assertEquals(405, get("/command").statusCode());
    }

    @Test
    void theEventStreamSendsTheCurrentStateAndThenEveryChange() throws Exception {
        hub.game().apply(new Message.Toggle(1, 1));

        try (EventStream events = openEventStream()) {
            assertEquals("data: state 0 false 100 1,1", events.next());

            post("step");

            assertEquals("data: state 1 false 100", events.next());
        }
    }

    @Test
    void anEventStreamIsAProperSseResponse() throws Exception {
        try (EventStream events = openEventStream()) {
            assertEquals("text/event-stream; charset=utf-8", events.contentType);
            assertEquals("no-store", events.cacheControl);
            events.next();
        }
    }

    /** The point of the whole exercise: a browser and a terminal editing one universe. */
    @Test
    void browserAndTerminalClientsShareTheSameUniverse() throws Exception {
        try (GameServer terminalServer = GameServer.start(hub, 0, log::add);
                EventStream events = openEventStream()) {
            BlockingQueue<Message.State> terminalStates = new LinkedBlockingQueue<>();
            try (GameClient terminal = GameClient.connect(
                    "localhost", terminalServer.port(), terminalStates::add)) {

                assertEquals(0, terminalStates.poll(5, TimeUnit.SECONDS).population());
                events.next();

                // An edit made in the browser reaches the terminal client.
                post("toggle 5 6");
                assertTrue(terminalStates.poll(5, TimeUnit.SECONDS).alive()
                        .contains(new Cell(5, 6)));

                // An edit made in the terminal reaches the browser.
                terminal.send(new Message.Toggle(7, 8));
                assertTrue(events.awaitLineContaining("7,8"));
            }
        }
    }

    private HttpResponse<String> get(String path) throws Exception {
        return client.send(
                HttpRequest.newBuilder(uri(path)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String command) throws Exception {
        return client.send(
                HttpRequest.newBuilder(uri("/command"))
                        .POST(HttpRequest.BodyPublishers.ofString(command))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + server.port() + path);
    }

    private EventStream openEventStream() throws Exception {
        HttpResponse<Stream<String>> response = client.send(
                HttpRequest.newBuilder(uri("/events")).GET().build(),
                HttpResponse.BodyHandlers.ofLines());
        assertEquals(200, response.statusCode());
        return new EventStream(response);
    }

    /** Reads a {@code text/event-stream} body, skipping the blank separators and heartbeats. */
    private static final class EventStream implements AutoCloseable {

        private final String contentType;
        private final String cacheControl;
        private final Stream<String> body;
        private final Iterator<String> lines;

        private EventStream(HttpResponse<Stream<String>> response) {
            this.contentType = response.headers().firstValue("content-type").orElse("");
            this.cacheControl = response.headers().firstValue("cache-control").orElse("");
            this.body = response.body();
            this.lines = body.iterator();
        }

        private String next() {
            while (lines.hasNext()) {
                String line = lines.next();
                if (line.startsWith("data: ")) {
                    return line;
                }
            }
            throw new AssertionError("the event stream ended before the next message");
        }

        private boolean awaitLineContaining(String fragment) {
            for (int i = 0; i < 10; i++) {
                if (next().contains(fragment)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public void close() {
            body.close();
        }
    }
}
