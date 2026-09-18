package life.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import life.core.Cell;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** End-to-end tests: a real server, real sockets, and two clients sharing one universe. */
@Timeout(30)
class GameServerTest {

    @TempDir
    Path patterns;

    private final List<String> log = new CopyOnWriteArrayList<>();
    private GameServer server;

    @BeforeEach
    void startServer() throws IOException {
        server = GameServer.start(0, patterns, log::add);
    }

    @AfterEach
    void stopServer() {
        server.close();
    }

    @Test
    void everyClientReceivesTheCurrentStateOnConnect() throws IOException {
        try (GameClient _ = connect(new RecordingListener())) {
            RecordingListener listener = new RecordingListener();
            try (GameClient _ = connect(listener)) {
                Message.State state = listener.nextState();

                assertEquals(0, state.generation());
                assertEquals(0, state.population());
                assertFalse(state.running());
            }
        }
    }

    @Test
    void anEditByOneClientIsSeenByAll() throws IOException {
        RecordingListener alice = new RecordingListener();
        RecordingListener bob = new RecordingListener();
        try (GameClient aliceClient = connect(alice); GameClient _ = connect(bob)) {
            alice.nextState();
            bob.nextState();

            aliceClient.send(new Message.Toggle(1, 2));

            assertEquals(
                    List.of(new Cell(1, 2)),
                    List.copyOf(alice.awaitState(s -> s.population() == 1, "with one cell").alive()));
            assertEquals(
                    List.of(new Cell(1, 2)),
                    List.copyOf(bob.awaitState(s -> s.population() == 1, "with one cell").alive()));
        }
    }

    @Test
    void aStepByOneClientAdvancesEveryonesGeneration() throws IOException {
        RecordingListener alice = new RecordingListener();
        RecordingListener bob = new RecordingListener();
        try (GameClient _ = connect(alice); GameClient bobClient = connect(bob)) {
            seedBlinker(bobClient);
            alice.awaitState(s -> s.population() == 3, "with the blinker");
            bob.awaitState(s -> s.population() == 3, "with the blinker");

            bobClient.send(new Message.Step());

            assertEquals(1, alice.awaitState(s -> s.generation() == 1, "at generation 1").generation());
            assertEquals(1, bob.awaitState(s -> s.generation() == 1, "at generation 1").generation());
        }
    }

    @Test
    void runningAdvancesGenerationsUntilStopped() throws IOException {
        RecordingListener listener = new RecordingListener();
        try (GameClient client = connect(listener)) {
            seedBlinker(client);
            client.send(new Message.SetSpeed(Game.MIN_SPEED_MILLIS));
            client.send(new Message.Start());

            Message.State running = listener.awaitState(s -> s.generation() >= 5, "at generation 5");
            assertTrue(running.running());
            assertEquals(3, running.population(), "a blinker keeps three cells forever");

            client.send(new Message.Stop());
            Message.State stopped = listener.awaitState(s -> !s.running(), "that is paused");

            Thread.sleep(200);
            assertFalse(listener.hasPendingState(), "no generations after stop");
            assertEquals(stopped.generation(), currentGeneration());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    @Test
    void editingWhileRunningKeepsTheGameRunning() throws IOException {
        RecordingListener listener = new RecordingListener();
        try (GameClient client = connect(listener)) {
            client.send(new Message.SetSpeed(Game.MIN_SPEED_MILLIS));
            client.send(new Message.Start());
            listener.awaitState(s -> s.generation() >= 2, "at generation 2");

            client.send(new Message.Toggle(0, 0));
            client.send(new Message.Toggle(1, 0));
            client.send(new Message.Toggle(2, 0));

            assertTrue(listener.awaitState(s -> s.generation() >= 10, "at generation 10").running());
        }
    }

    @Test
    void saveAndLoadTravelThroughTheServerForEveryone() throws IOException {
        RecordingListener alice = new RecordingListener();
        RecordingListener bob = new RecordingListener();
        try (GameClient aliceClient = connect(alice); GameClient bobClient = connect(bob)) {
            seedBlinker(aliceClient);
            alice.awaitState(s -> s.population() == 3, "with the blinker");

            aliceClient.send(new Message.Save("shared"));
            assertInstanceOf(Message.Notice.class, alice.nextReply());

            bobClient.send(new Message.Clear());
            bob.awaitState(s -> s.population() == 0, "that is empty");

            bobClient.send(new Message.Load("shared"));
            assertEquals(3, alice.awaitState(s -> s.population() == 3, "reloaded").population());
            assertEquals(3, bob.awaitState(s -> s.population() == 3, "reloaded").population());
        }
    }

    @Test
    void aFailedCommandIsReportedOnlyToTheClientThatSentIt() throws IOException {
        RecordingListener alice = new RecordingListener();
        RecordingListener bob = new RecordingListener();
        try (GameClient aliceClient = connect(alice); GameClient _ = connect(bob)) {
            alice.nextState();
            bob.nextState();

            aliceClient.send(new Message.Load("does-not-exist"));

            assertEquals(new Message.Error("no pattern named 'does-not-exist'"), alice.nextReply());
            assertFalse(bob.hasPendingState(), "the other client should not be disturbed");
        }
    }

    @Test
    void oneClientLeavingDoesNotDisturbTheOthers() throws IOException {
        RecordingListener alice = new RecordingListener();
        RecordingListener bob = new RecordingListener();
        try (GameClient bobClient = connect(bob)) {
            GameClient aliceClient = connect(alice);
            alice.nextState();
            bob.nextState();

            aliceClient.close();
            awaitClientCount(1);

            bobClient.send(new Message.Toggle(7, 7));
            assertEquals(1, bob.awaitState(s -> s.population() == 1, "with one cell").population());
            assertTrue(alice.isDisconnected());
        }
    }

    @Test
    void closingTheServerDisconnectsClientsAndFreesThePort() throws IOException {
        RecordingListener listener = new RecordingListener();
        int port = server.port();
        try (GameClient _ = connect(listener)) {
            listener.nextState();

            server.close();

            awaitDisconnect(listener);
        }
        try (ServerSocket reopened = new ServerSocket(port)) {
            assertEquals(port, reopened.getLocalPort());
        }
    }

    /** The protocol is plain text, so a game can be watched and driven with nothing but telnet. */
    @Test
    void speaksPlainTextToARawSocket() throws IOException {
        try (Socket socket = new Socket("localhost", server.port());
                BufferedReader in = new BufferedReader(
                        new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                PrintWriter out = new PrintWriter(
                        new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true)) {

            assertEquals("state 0 false 100", in.readLine());

            out.println("toggle 3 4");
            assertEquals("state 0 false 100 3,4", in.readLine());

            out.println("fly to the moon");
            assertTrue(in.readLine().startsWith("error "));

            out.println("step");
            assertEquals("state 1 false 100", in.readLine());
        }
    }

    private GameClient connect(GameClient.Listener listener) throws IOException {
        return GameClient.connect("localhost", server.port(), listener);
    }

    private static void seedBlinker(GameClient client) {
        client.send(new Message.Toggle(0, 0));
        client.send(new Message.Toggle(1, 0));
        client.send(new Message.Toggle(2, 0));
    }

    private long currentGeneration() {
        return server.game().state().generation();
    }

    private void awaitClientCount(int expected) {
        await(() -> server.clientCount() == expected, "client count to reach " + expected);
    }

    private static void awaitDisconnect(RecordingListener listener) {
        await(listener::isDisconnected, "the client to notice the disconnect");
    }

    private static void await(BooleanSupplier condition, String description) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
        throw new AssertionError("timed out waiting for " + description);
    }
}
