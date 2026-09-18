package life;

import java.io.IOException;
import java.io.PrintStream;
import java.net.ConnectException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import life.net.GameServer;
import life.net.Message;
import life.ui.Console;

/**
 * Entry point for both halves of the program: one server hosts the universe, any number of console
 * clients watch and edit it.
 *
 * <pre>
 * java -jar life.jar server [port] [pattern]
 * java -jar life.jar client [host[:port]]
 * </pre>
 */
public final class Main {

    private static final Path PATTERNS_DIRECTORY = Path.of("patterns");

    private static final String USAGE = """
            Conway's Game of Life, multiplayer.

              java -jar life.jar server [port] [pattern]        host a universe (default port %d)
              java -jar life.jar client [host[:port]] [--ascii] connect a console client

            Live cells are drawn as a solid block. Use --ascii to draw them as '#' instead, for
            terminals that cannot show it.

            Examples
              java -jar life.jar server
              java -jar life.jar server 7777 gosper-glider-gun
              java -jar life.jar client
              java -jar life.jar client 192.168.1.10:7777
              java -jar life.jar client --ascii
            """.formatted(GameServer.DEFAULT_PORT);

    private Main() {
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        if (args.length == 0) {
            fail(USAGE);
            return;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "server" -> server(args);
            case "client" -> client(args);
            default -> fail(USAGE);
        }
    }

    private static void server(String[] args) throws IOException, InterruptedException {
        int port = args.length > 1 ? port(args[1]) : GameServer.DEFAULT_PORT;
        PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);

        Files.createDirectories(PATTERNS_DIRECTORY);
        try (GameServer server = GameServer.start(port, PATTERNS_DIRECTORY, out::println)) {
            if (args.length > 2) {
                Message reply = server.game().load(args[2]);
                out.println(reply instanceof Message.Error error
                        ? "could not preload a pattern: " + error.text()
                        : "preloaded '" + args[2] + "'");
            }
            List<String> patterns = server.game().patternNames();
            out.println("patterns available to clients: "
                    + (patterns.isEmpty() ? "none yet" : String.join(", ", patterns)));
            out.println("press Ctrl-C to stop");

            CountDownLatch stopped = new CountDownLatch(1);
            Runtime.getRuntime().addShutdownHook(new Thread(stopped::countDown, "life-shutdown"));
            stopped.await();
            out.println("shutting down");
        }
    }

    private static void client(String[] args) throws IOException {
        String host = "localhost";
        int port = GameServer.DEFAULT_PORT;
        boolean preferAscii = false;
        for (int i = 1; i < args.length; i++) {
            String argument = args[i];
            if (argument.equals("--ascii")) {
                preferAscii = true;
                continue;
            }
            int colon = argument.lastIndexOf(':');
            host = colon < 0 ? argument : argument.substring(0, colon);
            if (colon >= 0) {
                port = port(argument.substring(colon + 1));
            }
        }
        try {
            Console.run(host, port, preferAscii);
        } catch (ConnectException e) {
            fail("could not connect to " + host + ":" + port + " - is the server running?");
        }
    }

    private static int port(String text) {
        try {
            int port = Integer.parseInt(text);
            if (port < 0 || port > 65535) {
                throw new NumberFormatException(text);
            }
            return port;
        } catch (NumberFormatException e) {
            fail("'" + text + "' is not a valid port");
            return 0; // unreachable: fail exits
        }
    }

    private static void fail(String message) {
        System.err.println(message);
        System.exit(2);
    }
}
