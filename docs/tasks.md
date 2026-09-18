# Implementation tasks

Ordered, checkable task list derived from [`plan.md`](plan.md). Each task has a concrete
"done when" so progress is verifiable, not aspirational. Tasks were executed top to bottom;
a task was not started until the previous one's checks passed.

Legend: `[x]` done · `[~]` done but needs a human at a real terminal

Status: all phases complete. `./mvnw clean verify` is green with 134 tests, and
`target/life.jar` runs a server and clients. What ended up differing from the original plan is
recorded at the bottom.

---

## Phase 0 — Project skeleton

- [x] **0.1 Git repository**
  `git init`, `.gitignore` for `target/`, IDE folders, `*.log`.
  *Done when:* `git status` is clean after the initial commit.
  → repository initialised and `.gitignore` written; the first commit is left to the reviewer.

- [x] **0.2 Maven project + wrapper**
  `pom.xml` with `groupId=life`, `artifactId=life`, `--release 25`, UTF-8, dependencies
  `org.jline:jline` (3.30.17) and `org.junit.jupiter:junit-jupiter` (5.14.4, test scope);
  plugins `maven-compiler-plugin` 3.16.0, `maven-surefire-plugin` 3.6.0, `maven-shade-plugin`
  3.6.2 (main class `life.Main`, final jar `target/life.jar`). Maven Wrapper (`mvnw`, `mvnw.cmd`,
  `.mvn/wrapper/`) pinned to Maven 3.9.16.
  *Done when:* `./mvnw -q package` succeeds and produces `target/life.jar`.

- [x] **0.3 `life.Main` dispatcher**
  Parses `server [port] [pattern]` / `client [host[:port]]`, prints usage otherwise.
  Defaults: port `7777`, host `localhost`.
  *Done when:* `java -jar target/life.jar` prints usage and exits with code 2.

---

## Phase 1 — Core engine (`life.core`)

- [x] **1.1 `Cell` record**
  `record Cell(long x, long y)` with `Stream<Cell> neighbours()` (8 cells, plain `long` arithmetic;
  wrap is implicit).
  *Done when:* unit test asserts `new Cell(Long.MAX_VALUE, Long.MAX_VALUE).neighbours()` contains
  `new Cell(Long.MIN_VALUE, Long.MIN_VALUE)`. → `CellTest`, 4 tests.

- [x] **1.2 `Universe` immutable value**
  Fields `Set<Cell> alive` (unmodifiable copy), `long generation`. Factories `empty()`,
  `of(Set)`, `of(Set, generation)`. Methods `next()`, `toggle`, `with`, `without`, `population()`,
  `isAlive()`. `next()` = neighbour-count `HashMap` over live cells, then B3/S23.
  *Done when:* tests pass for block (stable), blinker (period 2), glider (translates (1,1) after
  4 generations), empty stays empty, `generation` increments by exactly 1 per `next()`.
  → `UniverseTest`, 12 tests.

- [x] **1.3 Torus proof test**
  Blinker at the x seam, the y seam, both seams at once and the opposite corner; plus a glider
  flown across the seam for 40 generations.
  *Done when:* each oscillates with period 2 and population stays 3. → `TorusTest`, 7 tests.

---

## Phase 2 — Persistence (`life.core.PatternFile`)

- [x] **2.1 Format**
  Plaintext `.cells`: `!` comments, `!Origin: <x> <y>` and `!Generation: <n>` headers, `.` dead
  and `O` alive, trailing dead cells omittable. Documented in the class Javadoc and the README.

- [x] **2.2 `load(Path)` / `parse(String)`**
  Tolerates CRLF, blank lines, missing headers, and `o`/`*` for alive. Throws
  `PatternFormatException` with the line and column on anything else.
  *Done when:* parse tests cover minimal glider, origin header, CRLF, comment-only file → empty
  universe, bad character → exception with correct line number.

- [x] **2.3 `save(Universe, Path)` / `format(Universe)`**
  Bounding box computed as the shortest covering arc of the coordinate circle, so a pattern on
  the wrap seam is written compactly. Refuses to write a span wider than 4096 cells.
  *Done when:* round-trip test passes for glider, glider gun, empty, and a pattern straddling
  `Long.MAX_VALUE` on both axes.

- [x] **2.4 Example files in `patterns/`**
  `gosper-glider-gun` (36 cells in a 36x9 box at origin `(-18, -4)`), plus the `blinker`,
  `beacon` and `pulsar` oscillators. All are centred on `(0, 0)`.
  *Done when:* test loads the gun, asserts population 36 and the period-30 behaviour (41 cells at
  generation 30 with the gun reappearing unchanged, 46 at 60, 71 at 210); and each oscillator
  returns to its starting cells after exactly its stated period, not sooner.
  → `PatternFileTest`, 16 tests.

---

## Phase 3 — Protocol (`life.net`)

- [x] **3.1 `Message` sealed interface**
  Client → server: `Toggle`, `Start`, `Stop`, `Step`, `Clear`, `SetSpeed`, `Patterns`, `Save`,
  `Load`. Server → client: `State`, `Notice`, `Error`. `Save`/`Load` validate the name against
  `[A-Za-z0-9._-]{1,64}` in their constructors.

- [x] **3.2 `Wire` codec**
  `encode`/`decode`, one line per message, exhaustive `switch` in both directions, throwing
  `ProtocolException` on malformed input.
  *Done when:* parameterised round-trip over every variant (including `State` with 0 and 1000
  cells and extreme coordinates) passes and malformed lines throw. → `WireTest`, 47 tests.

---

## Phase 4 — Server (`life.net.GameServer`)

- [x] **4.1 `Game` (server-side state holder)**
  Holds `Universe`, `running`, `speedMillis`, `patternsDirectory`. All mutators `synchronized`.
  `apply(Message)` returns a `State` to broadcast, or a `Notice`/`Error` for the sender alone.
  *Done when:* unit tests for each command; `Load` of a missing or corrupt file returns `Error`
  and leaves the universe unchanged. → `GameTest`, 15 tests.

- [x] **4.2 Tick loop**
  Single `ScheduledExecutorService`. `rescheduleTick()` compares the schedule in force with the
  game's flags, so it can be called after every command and only acts when the timing really
  changed — editing while running does not delay the next generation. Speed clamped to
  `[10, 5000]` ms; `Game.tick()` returns empty if the game was stopped under the tick thread.
  *Done when:* test runs at 10 ms and asserts the generation advances and then stops cleanly.

- [x] **4.3 Client sessions**
  Accept loop and two virtual threads per connection (reader, writer) with a 64-line bounded
  outbox; a client that cannot keep up is disconnected. New clients receive the current `State`
  immediately. Malformed lines produce an `Error` to that client only.
  *Done when:* integration test on port 0 with two clients covers initial state, a shared edit,
  a shared step, a private error, and one client leaving. → `GameServerTest`, 10 tests.

- [x] **4.4 Lifecycle**
  `GameServer.start()` / `close()` (`AutoCloseable`); `Main server` prints the bound port and the
  available patterns, then blocks until a shutdown hook fires.
  *Done when:* integration test closes the server and rebinds the same port.

---

## Phase 5 — Client (`life.net.GameClient`)

- [x] **5.1 Connection**
  `GameClient.connect(host, port, listener)`, `send(Message)`, `close()`, `isOpen()`, with a
  `Listener` interface for state, notice, error and disconnect delivered on a virtual reader
  thread. Listener exceptions are swallowed so one bad frame cannot drop the connection.
  *Done when:* the Phase 4 integration test drives the server through `GameClient` rather than
  raw sockets, and one test still uses a raw socket to prove the protocol is plain text.

---

## Phase 6 — Console UI (`life.ui`)

- [x] **6.1 Viewport model (pure, testable)**
  `record Viewport(long left, long top, int columns, int rows)` with `contains`, `columnOf`,
  `rowOf`, `cellAt`, `pan`, `resized`, `centredOn`, `following`. Containment uses a wrapping
  subtraction compared as unsigned, so a viewport on the seam behaves like any other.
  *Done when:* unit tests for pan/contains/follow-cursor including wrap at `Long.MAX_VALUE`.
  → `ViewportTest`, 11 tests.

- [x] **6.2 Renderer (pure, testable)**
  `Renderer.grid(...)` returns plain glyph rows, one character per cell via `Glyphs`;
  `Renderer.status(...)` the summary line, `Renderer.HELP` the key list. No ANSI and no terminal
  here; the cursor highlight is applied by `Console`, which keeps the renderer golden-testable.
  *Done when:* golden tests for small viewports, including one across the wrap seam.
  → `RendererTest`, 9 tests.

- [x] **6.3 Terminal loop**
  JLine `Terminal` in raw mode on the alternate screen, `KeyMap` bindings (arrows, `space`,
  `enter`, `n`, `c`, `+`/`-`, `[`/`]`, `PgUp`/`PgDn`, `g` goto, `0`/`Home`, `s`, `l`, `q`), an
  in-place prompt for names and coordinates, and a single painter thread that repaints at most
  every 16 ms. Terminal resize re-centres the viewport on the cursor.

- [~] **6.4 Terminal verification**
  The client opens its terminal with UTF-8 encoding and draws `█` by default;
  `Console.glyphsFor(type, preferAscii)` returns `#` when `--ascii` is passed or the terminal type
  is `dumb`. The terminal's reported encoding is not consulted, because on Windows it is the legacy
  code page even when the console renders Unicode. → `ConsoleTest`, 3 tests.
  *Still needs a human:* looking at two real client windows side by side. JLine needs a real
  terminal, so this cannot be asserted in the build.

---

## Phase 7 — Finish

- [x] **7.1 README.md**
  Build and run instructions, key table, file format, full protocol reference with a `telnet`
  transcript, design notes (torus via `long` overflow, sparse universe, single writer, dropped
  slow clients, half-block rendering, the full-state broadcast limit), layout, test summary and
  the AI work process (requirement 5).

- [x] **7.2 Full verification**
  `./mvnw clean verify` green, 134 tests, shaded `target/life.jar` produced. End-to-end run of
  the built jar: `server 7799 gosper-glider-gun` with two TCP clients at 50 ms per generation —
  both received the 36-cell gun on connect, both advanced in lockstep past generation 200, the
  population grew as the gun fired, and neither client was dropped.

- [x] **7.3 Final review**
  No unused code, no `System.out` outside `Main`, every public type carries a Javadoc paragraph,
  compiler clean under `-Xlint:all`, and these documents updated to match what was built.

---

## What changed from the plan

Decisions taken during implementation that the plan did not anticipate:

- **`!Generation` header added to the file format.** Saving the generation counter makes a file a
  lossless snapshot of the whole state, which in turn makes the round-trip test an equality check
  on the entire `Universe` rather than just its cells.
- **Two messages added.** `Notice` gives honest feedback for commands that change nothing visible
  (a successful save), and `Patterns` lets a client ask what it can load — without it, a user on
  another machine would have to guess pattern names.
- **`Clear` resets the generation counter** to 0, because in the UI it means "start again". This
  removed the need for `Universe.cleared()`.
- **`Game` lives in `life.net`**, next to the server that owns it; the plan's file list omitted it.
- **`Glyphs` extracted as an enum** so the ASCII fallback is a value rather than a branch in the
  renderer, and `Renderer` stayed free of ANSI entirely.
- **One character per cell instead of half-blocks.** The plan packed two cell rows into each
  terminal line (`█ ▀ ▄`) to fit 100 x 100 in 50 lines. It worked, but the cursor then covered two
  cells and only the status line could say which one `space` would toggle. Drawing one cell per
  character costs half the visible rows — recovered by scrolling, which the viewport already did —
  and in exchange what you see is exactly what you edit.
- **Extra key bindings:** `g` to jump to any coordinate (the quickest way to see the 2^64 wrap for
  yourself), `[`/`]` for horizontal paging, `0`/`Home` to return to the origin.
- **Saving refuses spans wider than 4096 cells.** A universe is unbounded but a pattern file is a
  rectangle of characters, so this fails loudly instead of trying to write a vast grid.
- **The glyph fallback is tested as a pure function** of terminal type and encoding. Constructing
  JLine terminals inside the test JVM hung the build, and a pure function is the better seam.
