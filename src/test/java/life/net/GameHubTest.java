package life.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** The shared game and its fan-out, with no transport involved. */
@Timeout(30)
class GameHubTest {

    @TempDir
    Path patterns;

    private final List<String> log = new CopyOnWriteArrayList<>();
    private GameHub hub;

    @BeforeEach
    void createHub() {
        hub = new GameHub(patterns, log::add);
    }

    @AfterEach
    void closeHub() {
        hub.close();
    }

    @Test
    void aNewSubscriberIsSentTheCurrentStateAtOnce() {
        hub.submit(new Message.Toggle(4, 5));

        Lines lines = subscribe();

        assertEquals("state 0 false 100 4,5", lines.next());
    }

    @Test
    void everySubscriberSeesEveryChange() {
        Lines first = subscribe();
        Lines second = subscribe();
        first.next();
        second.next();

        hub.submit(new Message.Toggle(1, 2));

        assertEquals("state 0 false 100 1,2", first.next());
        assertEquals("state 0 false 100 1,2", second.next());
        assertEquals(2, hub.subscriberCount());
    }

    @Test
    void repliesThatChangeNothingAreNotBroadcast() {
        Lines lines = subscribe();
        lines.next();

        Message reply = hub.submit(new Message.Patterns());

        assertInstanceOf(Message.Notice.class, reply);
        assertTrue(lines.isEmpty(), "a notice belongs to the sender alone");
    }

    @Test
    void closingASubscriptionStopsDelivery() {
        Lines staying = subscribe();
        Lines leaving = subscribe();
        staying.next();
        leaving.next();

        leaving.subscription.close();
        assertFalse(leaving.subscription.isOpen());
        await(() -> hub.subscriberCount() == 1, "the subscriber count to drop");

        hub.submit(new Message.Toggle(9, 9));

        assertEquals("state 0 false 100 9,9", staying.next());
        assertTrue(leaving.isEmpty());
    }

    @Test
    void runningAdvancesGenerationsAndStoppingEndsThem() {
        Lines lines = subscribe();
        lines.next();

        hub.submit(new Message.SetSpeed(Game.MIN_SPEED_MILLIS));
        hub.submit(new Message.Start());

        await(() -> hub.game().state().generation() >= 5, "five generations to pass");
        hub.submit(new Message.Stop());
        long stopped = hub.game().state().generation();

        sleep(200);
        assertEquals(stopped, hub.game().state().generation(), "no generations after stop");
    }

    @Test
    void aSubscriberThatCannotKeepUpIsDroppedAndTheRestCarryOn() {
        Lines healthy = subscribe();
        healthy.next();

        // A sink that never returns models a client that has stopped reading its socket.
        GameHub.Subscription stalled = hub.subscribe(line -> sleep(60_000));

        // Each update waits for the healthy subscriber before the next one is sent, because the
        // buffer is there to absorb a slow reader and not a flood: with real traffic a generation
        // is at least ten milliseconds away. The stalled sink takes one line and never another, so
        // its queue fills and overflows while the healthy one stays empty.
        for (int i = 0; i < 80 && stalled.isOpen(); i++) {
            hub.submit(new Message.Toggle(i, 0));
            healthy.next();
        }

        await(() -> !stalled.isOpen(), "the stalled subscriber to be dropped");
        assertTrue(log.stream().anyMatch(line -> line.contains("too slow")), log.toString());

        assertTrue(healthy.subscription.isOpen(), "the healthy subscriber should be unaffected");
        hub.submit(new Message.Toggle(-1, -1));
        assertTrue(healthy.next().contains("-1,-1"), "the healthy subscriber should keep receiving");
    }

    @Test
    void theGameIsSharedSoAnyFrontEndCanSeedIt() {
        hub.game().apply(new Message.Toggle(7, 7));

        Lines lines = subscribe();

        assertEquals("state 0 false 100 7,7", lines.next());
    }

    private Lines subscribe() {
        Lines lines = new Lines();
        lines.subscription = hub.subscribe(lines.queue::add);
        return lines;
    }

    /** A subscriber that just collects the lines it is sent. */
    private static final class Lines {
        private final BlockingQueue<String> queue = new LinkedBlockingQueue<>();
        private GameHub.Subscription subscription;

        private String next() {
            try {
                String line = queue.poll(5, TimeUnit.SECONDS);
                if (line == null) {
                    throw new AssertionError("no line received within 5 seconds");
                }
                return line;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }

        private boolean isEmpty() {
            sleep(150); // give a wrongly sent line time to arrive
            return queue.isEmpty();
        }
    }

    private static void await(BooleanSupplier condition, String description) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            sleep(10);
        }
        throw new AssertionError("timed out waiting for " + description);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
