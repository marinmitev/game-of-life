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
import life.net.GameHub;
import life.net.GameServer;
import life.net.Message;
import life.ui.Console;
import life.web.WebServer;

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

              java -jar life.jar server [port] [pattern] [--web <port>] [--no-web]
                  Hosts one universe for terminal clients on port %d and for browsers on
                  http://localhost:%d. Both see the same game.

              java -jar life.jar client [host[:port]] [--ascii]
                  Connects a console client. Live cells are drawn as a solid block; --ascii draws
                  them as '#' for terminals that cannot show it.

            Examples
              java -jar life.jar server
              java -jar life.jar server 7777 gosper-glider-gun
              java -jar life.jar server --web 9000 --no-web
              java -jar life.jar client
              java -jar life.jar client 192.168.1.10:7777
              java -jar life.jar client --ascii
            """.formatted(GameServer.DEFAULT_PORT, WebServer.DEFAULT_PORT);

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
        int port = GameServer.DEFAULT_PORT;
        int webPort = WebServer.DEFAULT_PORT;
        String pattern = null;
        for (int i = 1; i < args.length; i++) {
            if (args[i].equals("--web")) {
                webPort = port(argumentAfter(args, i++, "--web"));
            } else if (args[i].equals("--no-web")) {
                webPort = -1;
            } else if (args[i].chars().allMatch(Character::isDigit)) {
                port = port(args[i]);
            } else {
                pattern = args[i];
            }
        }
        PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);

        Files.createDirectories(PATTERNS_DIRECTORY);
        // One hub, so a terminal client and a browser tab share a single universe.
        try (GameHub hub = new GameHub(PATTERNS_DIRECTORY, out::println);
                GameServer terminals = GameServer.start(hub, port, out::println);
                WebServer browsers = webPort < 0 ? null : WebServer.start(hub, webPort, out::println)) {
            if (pattern != null) {
                Message reply = hub.game().load(pattern);
                out.println(reply instanceof Message.Error error
                        ? "could not preload a pattern: " + error.text()
                        : "preloaded '" + pattern + "'");
            }
            List<String> patterns = hub.game().patternNames();
            out.println("patterns available to clients: "
                    + (patterns.isEmpty() ? "none yet" : String.join(", ", patterns)));
            out.println("connect a terminal client with: java -jar life.jar client localhost:"
                    + terminals.port());
            out.println("press Ctrl-C to stop");

            CountDownLatch stopped = new CountDownLatch(1);
            Runtime.getRuntime().addShutdownHook(new Thread(stopped::countDown, "life-shutdown"));
            stopped.await();
            out.println("shutting down");
        }
    }

    private static String argumentAfter(String[] args, int index, String option) {
        if (index + 1 >= args.length) {
            fail(option + " needs a port number");
        }
        return args[index + 1];
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
