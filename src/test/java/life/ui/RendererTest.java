package life.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import life.TestPatterns;
import life.core.Cell;
import life.net.Message;
import org.junit.jupiter.api.Test;

class RendererTest {

    @Test
    void drawsOneCharacterPerCell() {
        Set<Cell> block = TestPatterns.cells(0, 0, TestPatterns.BLOCK);

        List<String> lines = Renderer.grid(block, new Viewport(0, 0, 4, 3), Glyphs.BLOCKS);

        assertEquals(List.of("\u2588\u2588  ", "\u2588\u2588  ", "    "), lines);
    }

    @Test
    void drawsAGliderAsItIsWrittenInAPatternFile() {
        Set<Cell> glider = TestPatterns.cells(0, 0, TestPatterns.GLIDER);

        List<String> lines = Renderer.grid(glider, new Viewport(0, 0, 3, 3), Glyphs.BLOCKS);

        assertEquals(List.of(" \u2588 ", "  \u2588", "\u2588\u2588\u2588"), lines);
    }

    @Test
    void fallsBackToAsciiWithTheSameLayout() {
        Set<Cell> glider = TestPatterns.cells(0, 0, TestPatterns.GLIDER);

        List<String> lines = Renderer.grid(glider, new Viewport(0, 0, 3, 3), Glyphs.ASCII);

        assertEquals(List.of(" # ", "  #", "###"), lines);
    }

    @Test
    void ignoresCellsOutsideTheViewport() {
        Set<Cell> cells = Set.of(new Cell(0, 0), new Cell(100, 100), new Cell(-1, 0));

        List<String> lines = Renderer.grid(cells, new Viewport(0, 0, 2, 2), Glyphs.BLOCKS);

        assertEquals(List.of("\u2588 ", "  "), lines);
    }

    @Test
    void drawsCellsAcrossTheWrapSeam() {
        Set<Cell> cells = Set.of(new Cell(Long.MAX_VALUE, 0), new Cell(Long.MIN_VALUE, 1));

        List<String> lines = Renderer.grid(cells, new Viewport(Long.MAX_VALUE - 1, 0, 4, 2), Glyphs.BLOCKS);

        assertEquals(List.of(" \u2588  ", "  \u2588 "), lines);
    }

    @Test
    void hasOneLinePerViewportRow() {
        List<String> lines = Renderer.grid(Set.of(), new Viewport(0, 0, 7, 3), Glyphs.BLOCKS);

        assertEquals(3, lines.size());
        lines.forEach(line -> assertEquals(7, line.length()));
    }

    @Test
    void statusShowsEverythingNeededToDriveTheGame() {
        Message.State state = new Message.State(120, true, 100, Set.of(new Cell(-3, 7)));

        String status = Renderer.status(state, new Viewport(-60, -27, 120, 54), new Cell(-3, 7), "localhost:7777");

        assertEquals(
                "gen 120 | pop 1 | RUNNING | 100ms | cursor -3,7 alive | view -60,-27 120x54 | localhost:7777",
                status);
    }

    @Test
    void statusDistinguishesPausedAndDeadCursorCell() {
        Message.State state = new Message.State(0, false, 500, Set.of());

        String status = Renderer.status(state, new Viewport(0, 0, 10, 10), new Cell(1, 1), "-");

        assertTrue(status.contains("PAUSED"), status);
        assertTrue(status.contains("cursor 1,1 dead"), status);
    }

    @Test
    void helpMentionsEveryInteractiveAction() {
        assertTrue(Renderer.HELP.contains("space toggle"));
        assertTrue(Renderer.HELP.contains("enter run/pause"));
        assertTrue(Renderer.HELP.contains("q quit"));
    }
}
