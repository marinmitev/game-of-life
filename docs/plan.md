# Plan: Multiplayer Game of Life in Java

Implementation plan for the task described in [`requirements.md`](requirements.md).
The step-by-step task list lives in [`tasks.md`](tasks.md).

## Guiding decisions

- **Java 25 (LTS), Maven with wrapper, two dependencies only**: JLine 3 (console UI) and JUnit 5 (tests).
  Everything else — networking, threading, file I/O — is JDK. Compiled with `--release 25`.
  Language features relied on: records, sealed interfaces with exhaustive pattern-matching
  `switch`, virtual threads, unnamed variables (`_`). No preview features.
- **One executable jar, two modes**: `java -jar life.jar server [port]` and
  `java -jar life.jar client [host:port]`. One codebase, no duplicated model.
- **Pure core, I/O at the edges**: the simulation is an immutable value type with no threads,
  sockets or files in it. That keeps it trivially testable and lets the server treat
  "current state" as one atomic reference swap per generation.

## 1. Core model (`life.core`)

- `Cell(long x, long y)` — a record. The 2^64 × 2^64 torus comes for free: Java `long`
  arithmetic is two's-complement modulo 2^64, so `x + 1` at `Long.MAX_VALUE` wraps to
  `Long.MIN_VALUE`. No modulo code, no special cases, and it is exactly the toroidal wrap the
  requirement asks for. This is the single most important idea in the design and gets its own test.
- `Universe` — immutable: `Set<Cell> alive`, `long generation`. Methods: `next()`,
  `with(Cell)` / `without(Cell)` / `toggle(Cell)`, `empty()`, `of(cells)`.
  - `next()` is the standard sparse algorithm: build a neighbour-count map over live cells'
    8 neighbours, then apply B3/S23. Cost is O(live cells), independent of universe size —
    that is what makes "mostly empty, 2^64 wide" practical.
- No mutable grid anywhere. A 100×100 region is a *view* over the universe, not a storage format.

## 2. Persistence (`life.core.PatternFile`)

- Plaintext `.cells` format (the Life community standard: `!` comment lines, `.` dead, `O` alive),
  extended with one header line `!Origin: x y` giving the absolute coordinate of the pattern's
  top-left cell. Human-readable, diff-friendly, and the glider gun example is legible in the
  file itself.
- `save(Universe, Path)` writes the bounding box of all live cells plus origin; `load(Path)`
  reverses it. Round-trip is lossless for any universe, including one straddling the wrap seam
  (bounding box computed in wrapped arithmetic).
- Ship `patterns/gosper-glider-gun.cells` centred on the origin.

## 3. Protocol (`life.net`)

- Plain TCP, newline-delimited text. Simple to debug with `telnet` / `nc`, no framing library,
  no JSON dependency.
- `Message` is a sealed interface with records:
  - client → server: `Toggle(x, y)`, `Start`, `Stop`, `Step`, `Clear`, `SetSpeed(ms)`,
    `Save(name)`, `Load(name)`
  - server → client: `State(generation, running, speedMs, Set<Cell>)`, `Error(text)`
- `Wire.encode` / `Wire.decode` is a single exhaustive `switch` each way — the compiler
  guarantees no message is forgotten.
- Server broadcasts the **full live-cell set** every generation. Stateless per client, tolerant
  of a client joining mid-run, and for glider-gun-scale populations (hundreds of cells) it is a
  few KB per tick. The scaling limit is documented in the README rather than adding viewport
  subscriptions the task does not need.

## 4. Server (`life.net.GameServer`)

- One `Game` object owns the `Universe` plus `running` / `speed`; all mutation goes through
  `synchronized` methods, and every change (edit, step, load) triggers a broadcast, so all
  clients stay in lockstep — including while editing, which is what makes it genuinely multiplayer.
- One `ScheduledExecutorService` thread drives ticks at the configured interval.
- One **virtual thread per client** for reading commands; each client has a bounded outbound
  queue and its own writer thread. A slow or dead client is disconnected rather than stalling
  the simulation or the others.
- `Save` / `Load` names are constrained to a `patterns/` directory (no path traversal), since
  multiple clients can invoke it.

## 5. Console UI (`life.ui.Console`, JLine 3)

- Full-screen terminal rendering, **one character per cell** (`█` alive, space dead; `#` via
  `--ascii` or on a `dumb` terminal). The terminal is opened as UTF-8 so the block survives a
  legacy platform encoding. The viewport pans with the cursor, or by a screen with PageUp/PageDown, so the
  100×100 editing area is reachable in any window size and you can follow gliders well beyond it.
  (The plan originally called for half-block characters, two cell rows per line; that was dropped
  because a cursor covering two cells is harder to reason about than a smaller visible area.)
- Keys: arrows move cursor, `Space` toggle, `Enter` start/stop, `N` step, `C` clear,
  `+` / `-` speed, `S` save, `L` load, `Q` quit.
- Status line shows generation, population, running/paused, speed, cursor coordinates,
  connected host.
- The UI is a thin translator: key → `Message` out; `State` in → redraw. It holds no game logic,
  so two clients pressing keys concurrently just works.

## 6. Tests (JUnit 5)

- **Rules**: block still-life, blinker period 2, glider translates by (1, 1) every 4 generations.
- **Wrap**: a blinker across `Long.MAX_VALUE` / `Long.MIN_VALUE` in both axes oscillates
  correctly — the torus proof.
- **File**: save/load round-trip; glider gun file loads to exactly 36 cells and runs
  30 generations producing a glider.
- **Protocol**: encode/decode round-trip for every message variant.
- **Integration**: in-process server, two clients; one toggles a cell, both observe the same
  `State`; step advances both.

## 7. Deliverables and layout

```
pom.xml, mvnw, mvnw.cmd, .mvn/wrapper/
README.md                      run instructions, protocol, design notes, AI work process (req. 5)
docs/plan.md                   this document
docs/tasks.md                  the task checklist, and what changed while building
patterns/gosper-glider-gun.cells
src/main/java/life/Main.java
src/main/java/life/core/{Cell,Universe,PatternFile,PatternFormatException}.java
src/main/java/life/net/{Message,Wire,Game,GameServer,GameClient,ProtocolException}.java
src/main/java/life/ui/{Viewport,Glyphs,Renderer,Console}.java
src/test/java/life/...
```

Fifteen production classes, four of them one-screen value types. Build with `./mvnw package`,
producing a single shaded jar.

## Execution order

1. `pom.xml` + wrapper, `Cell`, `Universe`, rule tests (get the engine provably right first).
2. `PatternFile` + glider gun file + round-trip test.
3. `Message` / `Wire` + tests, then `GameServer` and `GameClient` + integration test.
4. `Console` UI, verified manually against a running server with two client windows.
5. README, final full build and test run.

## Assumptions

- Universe coordinates are signed `long`, with the 100×100 editor centred on `(0, 0)`
  (cells −50..49). "Centre" is arbitrary on a torus, so this is just where the glider gun file
  and default viewport live.
- Any connected client may edit and control the simulation (true multiplayer, no owner/observer
  roles).
- Save/load happens **on the server** (the state is the server's), by name into `patterns/`;
  clients only send the name.

## Environment prerequisites

- JDK 25 on `PATH` (verified: `java 25.0.4.1 LTS`, `javac 25.0.4.1`). Nothing else needs to be
  installed globally; the Maven wrapper downloads Maven itself.
- Plugin/library versions must support Java 25: `maven-compiler-plugin` ≥ 3.14, `maven-surefire-plugin` ≥ 3.5,
  `maven-shade-plugin` ≥ 3.6, JUnit 5 (latest 5.x), JLine 3 (latest 3.x). Exact versions are pinned
  in `pom.xml` at implementation time after checking Maven Central.
