package life.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;
import life.core.Cell;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class WireTest {

    /** Every message variant, so a new one added without a codec shows up here. */
    static List<Message> allVariants() {
        return List.of(
                new Message.Toggle(3, -7),
                new Message.Toggle(Long.MIN_VALUE, Long.MAX_VALUE),
                new Message.Start(),
                new Message.Stop(),
                new Message.Step(),
                new Message.Clear(),
                new Message.SetSpeed(120),
                new Message.Patterns(),
                new Message.Save("gosper-glider-gun"),
                new Message.Load("gosper-glider-gun"),
                new Message.State(0, false, 100, Set.of()),
                new Message.State(42, true, 250, Set.of(new Cell(1, 2))),
                new Message.State(
                        Long.MAX_VALUE,
                        true,
                        10,
                        Set.of(new Cell(Long.MIN_VALUE, Long.MAX_VALUE), new Cell(0, 0))),
                new Message.Error("no such pattern 'nope'"));
    }

    @ParameterizedTest
    @MethodSource("allVariants")
    void roundTripsEveryMessageVariant(Message message) {
        assertEquals(message, Wire.decode(Wire.encode(message)));
    }

    @ParameterizedTest
    @MethodSource("allVariants")
    void encodesToASingleLine(Message message) {
        String encoded = Wire.encode(message);

        assertEquals(1, encoded.lines().count());
        assertTrue(encoded.matches("[a-z]+.*"), encoded);
    }

    @Test
    void roundTripsALargeState() {
        Set<Cell> alive = new HashSet<>();
        IntStream.range(0, 1000).forEach(i -> alive.add(new Cell(i, -i)));
        Message.State state = new Message.State(7, true, 50, alive);

        assertEquals(state, Wire.decode(Wire.encode(state)));
    }

    @Test
    void usesTheDocumentedTextForm() {
        assertEquals("toggle 3 -7", Wire.encode(new Message.Toggle(3, -7)));
        assertEquals("start", Wire.encode(new Message.Start()));
        assertEquals("speed 120", Wire.encode(new Message.SetSpeed(120)));
        assertEquals("load glider", Wire.encode(new Message.Load("glider")));
        assertEquals("state 5 true 100 2,3", Wire.encode(
                new Message.State(5, true, 100, Set.of(new Cell(2, 3)))));
    }

    @Test
    void toleratesSurroundingWhitespaceAndMixedCase() {
        assertEquals(new Message.Start(), Wire.decode("  START  "));
        assertEquals(new Message.Toggle(1, 2), Wire.decode("Toggle 1   2"));
    }

    @Test
    void keepsErrorTextWithSpacesIntact() {
        assertEquals(new Message.Error("a b c"), Wire.decode("error a b c"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "",
        "fly 1 2",
        "toggle",
        "toggle 1",
        "toggle 1 2 3",
        "toggle one two",
        "speed 99999999999999999999",
        "save with space",
        "save ../escape",
        "load",
        "state",
        "state 1 maybe 100",
        "state 1 true 100 5",
        "state 1 true 100 a,b"
    })
    void rejectsMalformedLines(String line) {
        assertThrows(ProtocolException.class, () -> Wire.decode(line));
    }

    @Test
    void refusesToConstructMessagesWithUnusablePatternNames() {
        assertThrows(IllegalArgumentException.class, () -> new Message.Save("../etc/passwd"));
        assertThrows(IllegalArgumentException.class, () -> new Message.Load(""));
    }
}
