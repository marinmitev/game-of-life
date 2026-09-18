package life.net;

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
 * One running game and everybody watching it, independent of how they are connected.
 *
 * <p>This is where "multiplayer" actually lives. A front end turns its transport into two calls —
 * {@link #submit} for a command and {@link #subscribe} for a stream of updates — so the TCP server
 * and the browser server are peers over one universe rather than two copies of the same logic.
 *
 * <p>Each subscriber gets a small bounded queue and its own virtual thread. A client that stops
 * reading fills its queue and is dropped, which keeps one stalled viewer from slowing the
 * simulation or the other players.
 */
public final class GameHub implements AutoCloseable {

    /** Lines buffered per subscriber before it is considered too slow to keep up. */
    private static final int OUTBOX_CAPACITY = 64;

    /** A subscriber's handle on its own stream; closing it stops delivery. */
    public interface Subscription extends AutoCloseable {

        /** Whether this subscription is still receiving. */
        boolean isOpen();

        @Override
        void close();
    }

    private final Game game;
    private final Consumer<String> log;
    private final Set<Subscriber> subscribers = ConcurrentHashMap.newKeySet();
    private final ExecutorService writers = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService ticker =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "life-ticker");
                thread.setDaemon(true);
                return thread;
            });

    private final Object tickLock = new Object();
    private ScheduledFuture<?> tick;
    private boolean tickRunning;
    private int tickSpeedMillis;

    private volatile boolean closed;

    public GameHub(Path patternsDirectory, Consumer<String> log) {
        this.game = new Game(patternsDirectory);
        this.log = log;
    }

    /** The shared game, so a front end can inspect or seed it. */
    public Game game() {
        return game;
    }

    /** How many subscribers are currently receiving updates. */
    public int subscriberCount() {
        return subscribers.size();
    }

    /**
     * Carries out {@code command} and tells everyone who needs to know.
     *
     * @return what should go back to the client that sent the command: a {@link Message.State}
     *     that has already been broadcast to everyone, or a {@link Message.Notice} or
     *     {@link Message.Error} meant for the sender alone
     */
    public Message submit(Message command) {
        Message reply = game.apply(command);
        rescheduleTick();
        if (reply instanceof Message.State state) {
            broadcast(state);
        }
        return reply;
    }

    /**
     * Starts sending encoded protocol lines to {@code sink}, beginning with the current state so
     * that a client joining a game in progress is immediately up to date.
     *
     * <p>{@code sink} is called on the subscriber's own thread and may block on I/O; it must throw
     * to signal that the client has gone away.
     */
    public Subscription subscribe(Consumer<String> sink) {
        Subscriber subscriber = new Subscriber(sink);
        subscribers.add(subscriber);
        subscriber.start();
        subscriber.send(Wire.encode(game.state()));
        return subscriber;
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
        Set.copyOf(subscribers).forEach(Subscriber::close);
        writers.shutdownNow();
    }

    private void broadcast(Message message) {
        String line = Wire.encode(message);
        subscribers.forEach(subscriber -> subscriber.send(line));
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

    /** One watcher: a queue of pending lines and the thread that writes them out. */
    private final class Subscriber implements Subscription {

        private final Consumer<String> sink;
        private final BlockingQueue<String> outbox = new ArrayBlockingQueue<>(OUTBOX_CAPACITY);
        private volatile Future<?> writer;
        private volatile boolean open = true;

        private Subscriber(Consumer<String> sink) {
            this.sink = sink;
        }

        private void start() {
            writer = writers.submit(this::writeLoop);
        }

        private void send(String line) {
            if (open && !outbox.offer(line)) {
                log.accept("dropping a subscriber that is too slow to keep up");
                close();
            }
        }

        private void writeLoop() {
            try {
                while (true) {
                    sink.accept(outbox.take());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException e) {
                close(); // the sink says its client has gone
            }
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public void close() {
            if (!subscribers.remove(this)) {
                return;
            }
            open = false;
            Future<?> running = writer;
            if (running != null) {
                running.cancel(true);
            }
        }
    }
}
