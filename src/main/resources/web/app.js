'use strict';

// The universe is 2^64 x 2^64, so coordinates cannot be JavaScript numbers: anything past 2^53
// loses precision. They are BigInt everywhere, and the torus works the same way it does on the
// server -- a wrapping subtraction compared as an unsigned value. Pixel arithmetic stays in
// Number, because an offset that is inside the viewport is necessarily small.

const TWO64 = 1n << 64n;
const MASK = TWO64 - 1n;
const SIGN = 1n << 63n;

/** A coordinate reduced to the signed 64-bit value the server would use. */
function wrap(value) {
    const unsigned = value & MASK;
    return unsigned >= SIGN ? unsigned - TWO64 : unsigned;
}

/** The distance from `edge` to `position` going right or down, as an unsigned BigInt. */
function offset(position, edge) {
    return (position - edge) & MASK;
}

/** Whether `position` falls in the `size` cells starting at `edge`. */
function within(position, edge, size) {
    return offset(position, edge) < BigInt(size);
}

const MIN_CELL_PIXELS = 1;
const MAX_CELL_PIXELS = 40;
const SPEED_STEP_MILLIS = 20;

const canvas = document.getElementById('universe');
const context = canvas.getContext('2d');

const view = { left: -50n, top: -25n, cellPixels: 8, columns: 0, rows: 0 };
const game = { generation: 0n, running: false, speedMillis: 100 };

let cells = [];              // [{x, y}] parsed once per update, drawn many times
let alive = new Set();       // the raw "x,y" tokens, for instant membership tests
let cursor = { x: 0n, y: 0n };
let needsPaint = true;

// ---------------------------------------------------------------- talking to the server

/** Sends one line of the same protocol a telnet session would use. */
async function send(line) {
    try {
        const response = await fetch('command', { method: 'POST', body: line });
        const text = (await response.text()).trim();
        if (!response.ok) {
            show(text || response.statusText, true);
        } else if (text) {
            const space = text.indexOf(' ');
            show(space < 0 ? text : text.slice(space + 1), text.startsWith('error'));
        }
    } catch (failure) {
        show('could not reach the server', true);
    }
}

/** Applies a `state` line: the complete shared state, sent on every change. */
function applyState(line) {
    const parts = line.split(' ');
    game.generation = BigInt(parts[1]);
    game.running = parts[2] === 'true';
    game.speedMillis = Number(parts[3]);

    const tokens = parts.slice(4);
    alive = new Set(tokens);
    cells = tokens.map(token => {
        const comma = token.indexOf(',');
        return { x: BigInt(token.slice(0, comma)), y: BigInt(token.slice(comma + 1)) };
    });
    needsPaint = true;
}

function listen() {
    const events = new EventSource('events');
    events.onopen = () => setConnection('connected');
    events.onerror = () => setConnection('reconnecting', 'lost');
    events.onmessage = message => {
        const line = message.data;
        if (line.startsWith('state ')) {
            applyState(line);
        } else if (line.startsWith('notice ') || line.startsWith('error ')) {
            show(line.slice(line.indexOf(' ') + 1), line.startsWith('error'));
        }
    };
}

// ---------------------------------------------------------------- drawing

function resize() {
    const ratio = window.devicePixelRatio || 1;
    const width = canvas.clientWidth;
    const height = canvas.clientHeight;
    canvas.width = Math.max(1, Math.round(width * ratio));
    canvas.height = Math.max(1, Math.round(height * ratio));
    context.setTransform(ratio, 0, 0, ratio, 0, 0);
    view.columns = Math.max(1, Math.floor(width / view.cellPixels));
    view.rows = Math.max(1, Math.floor(height / view.cellPixels));
    needsPaint = true;
}

function paint() {
    const size = view.cellPixels;
    const width = canvas.clientWidth;
    const height = canvas.clientHeight;

    context.fillStyle = '#11141a';
    context.fillRect(0, 0, width, height);

    if (size >= 8) {
        context.strokeStyle = '#1d222b';
        context.lineWidth = 1;
        context.beginPath();
        for (let column = 0; column <= view.columns; column++) {
            context.moveTo(column * size + 0.5, 0);
            context.lineTo(column * size + 0.5, view.rows * size);
        }
        for (let row = 0; row <= view.rows; row++) {
            context.moveTo(0, row * size + 0.5);
            context.lineTo(view.columns * size, row * size + 0.5);
        }
        context.stroke();
    }

    const live = new Path2D();
    const inset = size >= 4 ? 1 : 0;
    for (const cell of cells) {
        if (!within(cell.x, view.left, view.columns) || !within(cell.y, view.top, view.rows)) {
            continue;
        }
        const column = Number(offset(cell.x, view.left));
        const row = Number(offset(cell.y, view.top));
        live.rect(column * size, row * size, size - inset, size - inset);
    }
    context.fillStyle = '#7fd1a6';
    context.fill(live);

    if (within(cursor.x, view.left, view.columns) && within(cursor.y, view.top, view.rows)) {
        context.strokeStyle = '#6aa9ff';
        context.lineWidth = 1;
        context.strokeRect(
            Number(offset(cursor.x, view.left)) * size + 0.5,
            Number(offset(cursor.y, view.top)) * size + 0.5,
            Math.max(2, size - 1),
            Math.max(2, size - 1));
    }

    document.getElementById('status').textContent =
        `gen ${game.generation} | pop ${cells.length} | ${game.running ? 'RUNNING' : 'PAUSED'}`
        + ` | ${game.speedMillis}ms | cursor ${cursor.x},${cursor.y}`
        + ` ${alive.has(`${cursor.x},${cursor.y}`) ? 'alive' : 'dead'}`
        + ` | view ${view.left},${view.top} ${view.columns}x${view.rows}`;
    document.getElementById('run').textContent = game.running ? 'Pause' : 'Run';
    document.getElementById('speed').textContent = `${game.speedMillis} ms`;
    document.getElementById('zoom').textContent = `${view.cellPixels} px`;
}

function frame() {
    if (needsPaint) {
        needsPaint = false;
        paint();
    }
    requestAnimationFrame(frame);
}

// ---------------------------------------------------------------- viewport

function pan(dx, dy) {
    view.left = wrap(view.left + BigInt(dx));
    view.top = wrap(view.top + BigInt(dy));
    needsPaint = true;
}

function centreOn(x, y) {
    view.left = wrap(x - BigInt(Math.floor(view.columns / 2)));
    view.top = wrap(y - BigInt(Math.floor(view.rows / 2)));
    needsPaint = true;
}

function cellAt(event) {
    const box = canvas.getBoundingClientRect();
    const column = Math.floor((event.clientX - box.left) / view.cellPixels);
    const row = Math.floor((event.clientY - box.top) / view.cellPixels);
    return { x: wrap(view.left + BigInt(column)), y: wrap(view.top + BigInt(row)) };
}

/** Zooms while keeping the cell under the pointer where it is. */
function zoom(delta, event) {
    const anchor = event ? cellAt(event) : { x: view.left, y: view.top };
    const box = canvas.getBoundingClientRect();
    const pointerColumn = event ? Math.floor((event.clientX - box.left) / view.cellPixels) : 0;
    const pointerRow = event ? Math.floor((event.clientY - box.top) / view.cellPixels) : 0;

    const wanted = view.cellPixels + delta;
    view.cellPixels = Math.min(MAX_CELL_PIXELS, Math.max(MIN_CELL_PIXELS, wanted));
    resize();

    const newColumn = Math.min(pointerColumn, view.columns - 1);
    const newRow = Math.min(pointerRow, view.rows - 1);
    view.left = wrap(anchor.x - BigInt(newColumn));
    view.top = wrap(anchor.y - BigInt(newRow));
    needsPaint = true;
}

// ---------------------------------------------------------------- input

let stroke = null; // { drawing: boolean, touched: Set<string> }
let panning = null; // { x, y } in pixels

canvas.addEventListener('contextmenu', event => event.preventDefault());

canvas.addEventListener('mousedown', event => {
    if (event.button === 0) {
        const cell = cellAt(event);
        cursor = cell;
        stroke = { drawing: !alive.has(`${cell.x},${cell.y}`), touched: new Set() };
        applyStroke(cell);
    } else {
        panning = { x: event.clientX, y: event.clientY };
    }
    event.preventDefault();
});

canvas.addEventListener('mousemove', event => {
    cursor = cellAt(event);
    needsPaint = true;

    if (stroke) {
        applyStroke(cursor);
    } else if (panning) {
        const dx = Math.round((panning.x - event.clientX) / view.cellPixels);
        const dy = Math.round((panning.y - event.clientY) / view.cellPixels);
        if (dx !== 0 || dy !== 0) {
            pan(dx, dy);
            panning = { x: event.clientX, y: event.clientY };
        }
    }
});

window.addEventListener('mouseup', () => {
    stroke = null;
    panning = null;
});

/**
 * Draws or erases one cell of a drag. The gesture decides once whether it is drawing or erasing,
 * from the first cell, so dragging over a mixed area does not leave a checkerboard behind.
 */
function applyStroke(cell) {
    const token = `${cell.x},${cell.y}`;
    if (stroke.touched.has(token)) {
        return;
    }
    stroke.touched.add(token);
    if (stroke.drawing !== alive.has(token)) {
        alive.add(token); // optimistic, corrected by the next state
        send(`toggle ${cell.x} ${cell.y}`);
    }
}

canvas.addEventListener('wheel', event => {
    zoom(event.deltaY < 0 ? 1 : -1, event);
    event.preventDefault();
}, { passive: false });

window.addEventListener('keydown', event => {
    if (event.ctrlKey || event.metaKey || event.altKey) {
        return;
    }
    const screenX = event.shiftKey ? view.columns : 1;
    const screenY = event.shiftKey ? view.rows : 1;
    const actions = {
        ArrowLeft: () => pan(-screenX, 0),
        ArrowRight: () => pan(screenX, 0),
        ArrowUp: () => pan(0, -screenY),
        ArrowDown: () => pan(0, screenY),
        PageUp: () => pan(0, -view.rows),
        PageDown: () => pan(0, view.rows),
        ' ': () => send(game.running ? 'stop' : 'start'),
        n: () => send('step'),
        c: () => send('clear'),
        '+': () => send(`speed ${game.speedMillis - SPEED_STEP_MILLIS}`),
        '=': () => send(`speed ${game.speedMillis - SPEED_STEP_MILLIS}`),
        '-': () => send(`speed ${game.speedMillis + SPEED_STEP_MILLIS}`),
        g: askGoto,
        s: () => askName('save'),
        l: () => askName('load'),
        0: () => { cursor = { x: 0n, y: 0n }; centreOn(0n, 0n); }
    };
    const action = actions[event.key];
    if (action) {
        action();
        event.preventDefault();
    }
});

function askName(command) {
    const name = window.prompt(command === 'save' ? 'Save the universe as:' : 'Load which pattern?');
    if (name) {
        send(`${command} ${name.trim()}`);
    }
}

function askGoto() {
    const typed = window.prompt(
        'Go to coordinates (x,y). The universe wraps, so try 9223372036854775807,0');
    if (!typed) {
        return;
    }
    const parts = typed.split(/[ ,]+/).filter(Boolean);
    try {
        if (parts.length !== 2) {
            throw new SyntaxError(typed);
        }
        cursor = { x: wrap(BigInt(parts[0])), y: wrap(BigInt(parts[1])) };
        centreOn(cursor.x, cursor.y);
    } catch (failure) {
        show('expected two whole numbers, for example 9223372036854775807,0', true);
    }
}

// ---------------------------------------------------------------- wiring

const clicks = {
    run: () => send(game.running ? 'stop' : 'start'),
    step: () => send('step'),
    clear: () => send('clear'),
    slower: () => send(`speed ${game.speedMillis + SPEED_STEP_MILLIS}`),
    faster: () => send(`speed ${game.speedMillis - SPEED_STEP_MILLIS}`),
    in: () => zoom(2, null),
    out: () => zoom(-2, null),
    save: () => askName('save'),
    load: () => askName('load'),
    patterns: () => send('patterns'),
    goto: askGoto,
    home: () => { cursor = { x: 0n, y: 0n }; centreOn(0n, 0n); }
};
for (const [id, action] of Object.entries(clicks)) {
    document.getElementById(id).addEventListener('click', action);
}

let messageTimer = 0;

function show(text, isError) {
    const element = document.getElementById('message');
    element.textContent = text;
    element.classList.toggle('bad', Boolean(isError));
    window.clearTimeout(messageTimer);
    messageTimer = window.setTimeout(() => { element.textContent = ''; }, 5000);
}

function setConnection(text, className) {
    const element = document.getElementById('connection');
    element.textContent = text;
    element.className = className || 'connected';
}

window.addEventListener('resize', resize);
resize();
centreOn(0n, 0n);
listen();
requestAnimationFrame(frame);

// Opening the page with #selftest checks the coordinate arithmetic that the Java test suite
// cannot reach, in particular the two places where the universe wraps.
if (window.location.hash === '#selftest') {
    const MAXIMUM = 9223372036854775807n;
    const MINIMUM = -9223372036854775808n;
    const checks = [
        ['wrap leaves an ordinary value alone', wrap(42n) === 42n],
        ['wrap turns 2^63 into the minimum', wrap(SIGN) === MINIMUM],
        ['the minimum is one right of the maximum', offset(MINIMUM, MAXIMUM) === 1n],
        ['the maximum is one left of the minimum', offset(MAXIMUM, MINIMUM) === MASK],
        ['a viewport on the seam contains both sides',
            within(MAXIMUM, MAXIMUM - 1n, 4) && within(MINIMUM, MAXIMUM - 1n, 4)],
        ['a cell outside a viewport is excluded', !within(0n, MAXIMUM - 1n, 4)],
        ['panning past the maximum wraps', wrap(MAXIMUM + 1n) === MINIMUM]
    ];
    const failed = checks.filter(([, passed]) => !passed);
    show(failed.length === 0
        ? `self-test: all ${checks.length} coordinate checks passed`
        : `self-test failed: ${failed.map(([name]) => name).join('; ')}`, failed.length > 0);
}
