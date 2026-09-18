package life.net;

import static org.junit.jupiter.api.Assertions.fail;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/** Collects what a {@link GameClient} receives so tests can wait for a specific state. */
final class RecordingListener implements GameClient.Listener {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final BlockingQueue<Message.State> states = new LinkedBlockingQueue<>();
    private final BlockingQueue<Message> replies = new LinkedBlockingQueue<>();
    private final List<Message.State> seen = new ArrayList<>();
    private volatile boolean disconnected;

    @Override
    public void onState(Message.State state) {
        states.add(state);
    }

    @Override
    public void onNotice(Message.Notice notice) {
        replies.add(notice);
    }

    @Override
    public void onError(Message.Error error) {
        replies.add(error);
    }

    @Override
    public void onDisconnect() {
        disconnected = true;
    }

    /** The next state received, failing the test if none arrives. */
    Message.State nextState() {
        return next(states, "state");
    }

    /** Waits for a state matching {@code condition}, ignoring any that arrive before it. */
    Message.State awaitState(Predicate<Message.State> condition, String description) {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            Message.State state = nextState();
            seen.add(state);
            if (condition.test(state)) {
                return state;
            }
        }
        return fail("no state " + description + " within " + TIMEOUT + "; saw " + seen);
    }

    /** The next notice or error received, failing the test if none arrives. */
    Message nextReply() {
        return next(replies, "notice or error");
    }

    boolean hasPendingState() {
        return !states.isEmpty();
    }

    boolean isDisconnected() {
        return disconnected;
    }

    private static <T> T next(BlockingQueue<T> queue, String what) {
        try {
            T taken = queue.poll(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            return taken != null ? taken : fail("no " + what + " received within " + TIMEOUT);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return fail("interrupted while waiting for a " + what);
        }
    }
}
