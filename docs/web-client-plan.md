# Plan: browser client on an HTML canvas

A second front end for the same universe: a canvas in the browser, alongside the console client
from [`plan.md`](plan.md). Both connect to one server instance and edit one shared game.

## Transport: Server-Sent Events plus POST

A browser cannot open a raw TCP socket, so the transport is the only real decision. The existing
line protocol is reused verbatim:

| Direction | Mechanism |
| --- | --- |
| server → client | `GET /events`, `text/event-stream`, each broadcast written as `data: <protocol line>` |
| client → server | `POST /command` with one protocol line as the body |

The traffic is asymmetric in exactly the way SSE is built for: a continuous one-way stream of
`state` lines out, and rare one-line commands in from a human pressing keys.

WebSocket was the alternative and was rejected: the JDK ships a WebSocket *client*
(`java.net.http.WebSocket`) but no server, so it would mean hand-rolling RFC 6455 framing or
adding Jetty/Undertow — a large dependency for a program whose entire server is a few hundred
lines. `com.sun.net.httpserver.HttpServer` is already in the JDK, SSE needs no framing code, and
the browser side is `new EventSource('/events')`.

The cost is one HTTP request per user action, which is irrelevant at human speed.

## Server side: one shared hub, two front ends

`GameServer` currently owns four things: the `Game`, the tick schedule, the fan-out to clients,
and TCP. The first three are transport-agnostic and the HTTP front end needs all of them, so they
move into a new `life.net.GameHub`:

```java
public final class GameHub implements AutoCloseable {
    public Message submit(Message command);               // apply + broadcast, returns the sender's reply
    public Subscription subscribe(Consumer<String> sink);  // current state, then every change
    public Game game();
}
```

`Subscription` owns the bounded outbox and its writer virtual thread, so "drop a client that
cannot keep up" is written once and both front ends inherit it. `GameServer` shrinks to socket
handling and `life.web.WebServer` becomes its peer rather than a fork of it.

`Game`, `Universe`, `Wire` and `Message` are untouched: the browser speaks the same protocol as
`telnet`.

## Endpoints

| Route | Purpose |
| --- | --- |
| `GET /` | the page, from `src/main/resources/web/index.html` on the classpath |
| `GET /app.js`, `GET /style.css` | the client |
| `GET /events` | SSE stream; the current state arrives first, so a tab can join a running game |
| `POST /command` | one protocol line; `204` on success, `200` with `notice`/`error` text, `400` if unreadable |

Assets are served from the classpath so the shaded jar stays a single self-contained artifact.
`HttpServer` runs on a virtual-thread executor, because each SSE connection holds its thread for
as long as the tab is open. A `: ping` comment every 15 seconds keeps idle streams alive and
detects tabs that have gone away.

## The hard part: 2^64 coordinates in JavaScript

JavaScript numbers lose precision above 2^53, so `9223372036854775807` cannot be a `Number`.
Coordinates are `BigInt` throughout, and the Java wrap trick ports over directly:

```js
const MASK = (1n << 64n) - 1n;
const contains = (x, left, columns) => ((x - left) & MASK) < BigInt(columns);
const offset = (x, left) => Number((x - left) & MASK);   // safe: only called when contained
```

`BigInt` appears only in the viewport origin and in parsing; pixel arithmetic stays in `Number`,
because an offset inside the viewport is necessarily small. Cell identity uses the raw `"x,y"`
token from the wire as a `Set` key, so membership costs no parsing at all.

## Canvas client

- One `fillRect` per live cell, batched into a single `Path2D` and one `fill()` per frame;
  `devicePixelRatio` scaling for high-DPI screens; grid lines only above roughly 8 px per cell.
  Cost is O(population) per frame, matching the server's sparse model.
- Repaint on `requestAnimationFrame` driven by a dirty flag set by the SSE handler — the same
  coalescing idea as the painter thread in `Console`, so a 10 ms tick cannot outrun the display.
- Click toggles a cell; click-drag paints a stroke; right-drag or space-drag pans; the wheel zooms
  about the pointer; buttons and keys for run/pause, step, clear, speed, save and load; a "go to"
  field that accepts `9223372036854775807,0` so the wrap seam can be visited from a browser.
- A status bar with the same fields as the console client: generation, population, running, speed,
  viewport rectangle, cursor cell.

## Known limits, stated up front

- Same full-state broadcast as the TCP front end, so the same population ceiling.
- No authentication and no TLS: anyone who can reach the port can edit the universe, exactly as
  with the TCP port. Fine on a trusted network, and said out loud rather than discovered.

---

# Tasks

Legend: `[x]` done · `[~]` done but needs a human at a browser

- [x] **W1 Extract `GameHub`**
  Move the game reference, the tick schedule (`rescheduleTick`, `advance`) and the fan-out out of
  `GameServer`, together with the bounded outbox now living in `Session`. Add `submit`,
  `subscribe` and `Subscription`.
  *Done when:* `GameServerTest` still passes untouched, and `GameHubTest` covers fan-out to
  several subscribers, unsubscribe, tick start/stop, and a slow subscriber being dropped without
  disturbing the others.

- [x] **W2 `life.web.WebServer`**
  `HttpServer` on a virtual-thread executor with the four routes above, a 15-second SSE heartbeat,
  and classpath asset serving with correct content types.
  *Done when:* `WebServerTest` (using `java.net.http.HttpClient`) covers: `GET /` serves the page;
  `POST /command` changes the game; `GET /events` delivers the current state and then a further
  one after a command, read with `BodyHandlers.ofLines()`; a malformed command returns `400` with
  the reason; an unknown path returns `404`.

- [x] **W3 Cross-transport test**
  A TCP `GameClient` and an HTTP subscriber on one hub: an edit made over HTTP reaches the TCP
  client and an edit made over TCP reaches the browser stream.
  *Done when:* both directions assert the same universe, which is what proves the multiplayer
  claim across front ends.

- [x] **W4 Page and canvas**
  `index.html`, `style.css`, `app.js` with the `BigInt` viewport, canvas renderer, SSE wiring and
  the controls listed above.
  *Done when:* the page runs the Gosper gun next to a console client on the same server.

- [x] **W5 `Main` wiring**
  `server [port] [--web <port>] [pattern]` starting one hub with both front ends; default TCP
  7777 and HTTP 8080. The startup banner prints both addresses.
  *Done when:* `java -jar target/life.jar server` prints a `http://localhost:8080` line that opens
  a working page.

- [x] **W6 Docs**
  README section for the browser client, the endpoint table, the `BigInt` note, and the security
  limit. `plan.md` layout updated.

- [ ] **W7 Full verification**
  `./mvnw clean verify` green; one server, one browser tab and one console client all editing the
  same universe.

## What changed from the plan

- **The stroke gesture needed no new message.** Dragging over a mixed area would leave a
  checkerboard if every cell were toggled, and the protocol has no "set this cell alive". The
  client already knows the live set, so the gesture decides once whether it is drawing or erasing
  and sends `toggle` only for the cells that disagree. Nothing was added to the wire.
- **`--no-web`** was added alongside `--web <port>`, because the tests and the original terminal
  workflow should not have to bind a second port.
- **`GameHub` took the bounded outbox with it**, so `GameServer.Session` shrank to a socket, a
  subscription and a reader. `Session.write` became synchronized: broadcasts now arrive on the
  hub's thread while error replies are still written on the session's own thread.
- **The slow-subscriber test paces its burst.** Sending two hundred updates in a tight loop
  overflows *any* subscriber's buffer, healthy or not, so the first version of the test failed for
  the wrong reason. It now waits for the healthy subscriber between updates, which is what real
  traffic does anyway — a generation is at least ten milliseconds away.
- **`GameServerTest` was not touched**, which was the point of doing the extraction first.
