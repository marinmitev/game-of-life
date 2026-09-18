package life.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ConsoleTest {

    @Test
    void usesHalfBlocksOnAUtf8Terminal() {
        assertEquals(Glyphs.BLOCKS, Console.glyphsFor("xterm-256color", StandardCharsets.UTF_8));
        assertEquals(Glyphs.BLOCKS, Console.glyphsFor("windows-vtp", StandardCharsets.UTF_8));
    }

    @Test
    void fallsBackToAsciiOnADumbTerminal() {
        assertEquals(Glyphs.ASCII, Console.glyphsFor("dumb", StandardCharsets.UTF_8));
        assertEquals(Glyphs.ASCII, Console.glyphsFor("dumb-color", StandardCharsets.UTF_8));
        assertEquals(Glyphs.ASCII, Console.glyphsFor(null, StandardCharsets.UTF_8));
    }

    @Test
    void fallsBackToAsciiWhenTheTerminalCannotShowUtf8() {
        assertEquals(Glyphs.ASCII, Console.glyphsFor("xterm", StandardCharsets.US_ASCII));
        assertEquals(Glyphs.ASCII, Console.glyphsFor("xterm", StandardCharsets.ISO_8859_1));
    }
}
