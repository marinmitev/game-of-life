package life.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ConsoleTest {

    @Test
    void drawsSolidBlocksOnAnyRealTerminal() {
        assertEquals(Glyphs.BLOCKS, Console.glyphsFor("xterm-256color", false));
        assertEquals(Glyphs.BLOCKS, Console.glyphsFor("windows-vtp", false));
        assertEquals(Glyphs.BLOCKS, Console.glyphsFor("windows", false));
    }

    @Test
    void usesAsciiWhenAskedTo() {
        assertEquals(Glyphs.ASCII, Console.glyphsFor("xterm-256color", true));
    }

    @Test
    void usesAsciiOnADumbTerminal() {
        assertEquals(Glyphs.ASCII, Console.glyphsFor("dumb", false));
        assertEquals(Glyphs.ASCII, Console.glyphsFor("dumb-color", false));
        assertEquals(Glyphs.ASCII, Console.glyphsFor(null, false));
    }
}
