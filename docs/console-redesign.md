# The console

TradeyCLI's terminal front end: `bridge/`, opened by `TradeyCLI` with no arguments. This document
is its design: the goals, the library decision, the architecture, and a log of what landed and
why (section 12). It was written on 2026-09-05 as the plan for a second front end built beside
the Kotter dashboard so the working one kept working; the dashboard was retired on 2026-09-06
once the console had passed it, so sections 2 and 3 now describe history and the rest describes
the console as it is. When it was written: the engine was headless (`engine.Engine`, snapshots
out as a `StateFlow`, happenings as a `SharedFlow`), the dashboard was a reader of it, and
`screen/` was about 1,100 lines of Kotter that rendered an eight-column grid at a fixed 160
columns with keyboard-only, mostly unfinished, controls.

## 1. Goals, in order

1. **Aesthetics.** A console that looks detailed, colourful, interactive, and above all *alive*:
   things shift, animate, and carry visual interpretations of the game's objects beyond
   "asteroid here, ship there". Planets with atmospheres that turn, ships with silhouettes and
   flight paths, a system seen from above and, later, at an angle with depth.
2. **Density and utility.** What the agent is doing, its plan, its health, and how far it is
   towards its goals, on one screen, with the detail one click away.
3. **Debugging and analysis.** Errors, stale data, conflicting orders, API pressure, and the
   market and money trends that decide strategy. Where this cannot fit the look, it gets its own
   screen rather than breaking the look.

Controls: keyboard first, left click required, right click a nicety. Windows first: the target is
Windows Terminal on Windows 11. Everything must remain drivable from a shell with no TTY, because
that is how tests and an assistant see it.

## 2. What the current layer is and why it fights the goals

- One Kotter `section` repaints everything on every state change. Kotter's model is an
  append-only scrollback with a live area at the bottom that is erased and rewritten; there is no
  cell buffer, no alternate screen, and no diffing. Animating fifty rows this way flickers and
  burns CPU.
- Kotter's input is keys only. Its `Terminal` interface is `read(): SharedFlow<Int>`, `write`,
  `size`, `clear`, `close`. Mouse tracking would mean writing the escape sequences ourselves and
  parsing the replies out from under Kotter's own key parser, which is a fork, not a feature.
- Layout is `kotterx.grid` with the width from `profile.settings.json`, so a resize breaks the
  grid, and the boot menu (`Start`, `New`) is a Kotter screen too.
- The model imports Kotter types (`Notification` holds a `TextAnim`), and stray `println` calls
  in `screen/` write into the same console Kotter paints.

Most of this is structure, not Kotter, and any library dropped into it would inherit it. The new
front end starts from the engine's `Snapshot` and `Event` and owns its own screen.

## 3. Library evaluation

What the goals demand of a terminal layer, in order of how hard it is to add later:

| Need | Why |
|---|---|
| Raw mode with key **and mouse** events on Windows Terminal | left click is a requirement |
| Terminal size and resize notification | no more 160-column constant |
| 24-bit colour, alternate screen, cursor control | the look; a clean exit |
| A way to paint a whole frame cheaply | animation at 10 to 30 frames a second without tearing |
| Runs headless for one frame | golden tests, and an assistant that cannot open a TTY |
| Kotlin, maintained, compatible with Kotlin 2.4 | this is a Kotlin codebase on a current toolchain |

Widgets, layout managers and windowing systems are *not* on the list. The aesthetic goal is a
canvas: a grid of coloured cells that we draw planets, paths and charts into every frame. Widget
libraries are built for forms and tables and get in the way of that; the tables and panels we do
need are a few hundred lines on top of a cell buffer.

### Candidates

**Kotter 1.4.0** (current). Kotlin, maintained by one person, releases in 2024, 2025 and 2026.
Sections that re-render in place, `width`/`height` exposed, keyboard input only, no alternate
screen, a Swing "virtual terminal" fallback when there is no VT console. Fine for what it was
designed for, a dynamic scrollback, which is not what we are building. **No: no mouse, no
frame model.**

**Mosaic 0.18.0** (Aug 2025). Jake Wharton's Compose for the terminal. Declarative layout,
`Modifier.onKeyEvent`, its own terminal library since 0.17, Windows size reporting fixed in 0.18.
The changelog has never mentioned mouse input. It needs the Compose compiler plugin, which
couples us to whatever Kotlin version Mosaic was released against, and Compose's recomposition
model is a poor fit for a canvas that changes every frame anyway. **No: no mouse, version
coupling, wrong shape.**

**Lanterna 3.1.5** (Mar 2026). Mature Java. Its `Screen` layer is exactly a cell buffer with diff
refresh, and it has `MouseCaptureMode`. But on Windows the default factory opens a Swing window;
a real console needs the separate `native-integration` module (JNA to kernel32), which the
project describes as experimental and partially functional, and the maintainers say mouse support
on the Windows console "isn't very good". **No: Windows-first rules it out.**

**Jexer 2.0.0**. Java, Turbo Vision style windows, full mouse, sixel images. "100% Java, no native
libraries" means it uses `stty` for raw mode, so on Windows it runs inside its own Swing window,
not in Windows Terminal; and the windowed look is not ours. **No.**

**JLine 3.30.16**. Already on our classpath (Kotter pulls it in). Java. `TerminalBuilder` with
FFM, JNI and Jansi providers; `enterRawMode()`; `trackMouse(MouseTracking)` and
`readMouseEvent()` with the project's own compatibility table listing Windows Terminal as
supporting clicks, movement, wheel and extended reporting; `getSize()` and `WINCH`; `Display` for
line-diffed repaints; 24-bit `AttributedStyle`. Solid and proven (it is the terminal under the
Kotlin REPL and Spring Shell). Its key input is a raw stream of ints you decode yourself with
`KeyMap`/`BindingReader`, which is workable but clunky from Kotlin. **Viable. The fallback.**

**Mordant 3.1.0** (Aug 2026). Kotlin multiplatform, by the author of Clikt, active. `Terminal` with
`enterRawMode(mouseTracking)`, a unified `receiveEvents` / `readEvent` loop that yields
`KeyboardEvent(key, ctrl, alt, shift)` and `MouseEvent(x, y, left, right, middle, wheelUp,
wheelDown, ctrl, alt, shift)`, `MouseTracking` levels (buttons only, drag, any motion), terminal
size with resize handled, `rawPrint`, cursor control, 24-bit colour with capability detection,
and `mordant-coroutines` for flows. JVM native access is a pluggable module: `mordant-jvm-jna`
works on every JDK; `mordant-jvm-ffm` needs JDK 22 and `--enable-native-access`. The 3.1.0 notes
fix mouse wheel and button mapping and Windows timeout exceptions, so this path is exercised.
Its widgets (tables, panels, progress) we may borrow for the boring parts or ignore. **Yes, as
the thin layer.**

**Not on the JVM** (Ratatui in Rust, Textual in Python, Notcurses). Ratatui in particular is the
best tool anywhere for this exact aesthetic: a braille canvas, charts, mouse, and Windows via
crossterm. The dashboard is already a reader of the SQLite store, so a second-language front end
is technically possible. It would still lose the in-process snapshot (ship phases, the pacer's
queue history, API stats, the event stream) unless the engine grew a socket or a file protocol,
and it doubles the toolchain for a solo project. **Not now.** Nothing about the Kotlin path
prevents it later, because the store is the interface.

### Decision

**Mordant as the terminal layer, our own canvas above it.** This is the "write our own" answer
with the part that is genuinely hard on Windows, raw mode and native console input, bought in.
The cell buffer, the diff renderer, the layout, the widgets and every effect are ours, which is
what the first goal needs anyway: no widget library ships a planet with a rotating atmosphere.

JLine stays as the named fallback. The spike in milestone 0 exercises the risky parts of Mordant
on Windows Terminal; if any fail, the same adapter interface is implemented over JLine, which is
already resolving on the classpath, and nothing above it changes.

Dependencies to add (versions current as of this writing):

```kotlin
implementation("com.github.ajalt.mordant:mordant:3.1.0")
implementation("com.github.ajalt.mordant:mordant-jvm-jna:3.1.0")   // works on the 17 toolchain and on 22
implementation("com.github.ajalt.mordant:mordant-coroutines:3.1.0")
```

Kotter leaves when the new console reaches parity (milestone 3); JLine leaves with it unless we
have chosen it.

## 4. Architecture

Package `bridge/` (the ship's bridge; `TradeyCLI bridge` starts it). Layers, bottom up, each
testable without the one above:

```
bridge/tty        Tty: enter/leave (alt screen, raw mode, mouse tracking, cursor hide),
                  size, Flow<Input> (Key, Mouse, Resize, Tick), flush(bytes). Mordant behind it.
bridge/canvas     Cell(glyph, fg, bg, style), Surface(w, h) with clipped sub-surfaces,
                  Painter (text, boxes, lines, braille/half-block plotting),
                  FrameDiff: previous vs next surface -> minimal ANSI, wrapped in
                  synchronized output (ESC[?2026h ... ESC[?2026l) so a frame lands whole.
bridge/scene      Layout (Row/Column with Fixed/Weight/Min, Panel with title and border),
                  Widget(rect, paint, onKey, onMouse), focus ring and hit testing,
                  the standard widgets: Table, Sparkline, Bars, LineChart, Gauge, Feed,
                  StatusBar, CommandLine (editor with history and completion).
bridge/fx         Clock and tweens; Effect(update(dt), paint(surface)); the procedural art:
                  Starfield, Planet, Asteroid, Station, GateRing, ShipSprite, Trail, Flash.
bridge/glyphs     The glyph atlas: WaypointType, ShipRole and frame, TradeSymbol groups,
                  ShipNavStatus, Tone -> glyph + colour. One file, the thing to iterate on.
bridge/model      ViewModel: the latest Snapshot, recent Events, store history (prices,
                  transactions, credits, request_log), derived tables reused from
                  behaviour.decisions (Summary, Intentions, CreditsTrend, Trading, Mining).
bridge/views      One class per screen (section 6). Pure: (ViewModel, size, time) -> Surface.
bridge/Bridge.kt  Boot, the frame loop, the input router, the command line's dispatcher.
```

Rules that keep it fast and testable:

- **Rendering is a pure function** of (view model, size, clock). No widget holds terminal
  handles. The same function draws a frame to a TTY or to a string for a test or `--frame`.
- **State changes mark dirty; the clock drives frames.** The engine's `StateFlow` and
  `SharedFlow` update the view model; a ticker renders at up to 20 frames a second while any
  effect is animating and drops to about 4 when nothing moves. A frame that diffs to nothing
  writes nothing.
- **Width-1 glyphs only.** Box drawing, block elements, shades, braille (U+2800 block gives
  2×4 dots per cell), geometric shapes, arrows, Latin. No emoji, no CJK, so column arithmetic
  never lies. Half-block (▀ ▄) with distinct fg/bg gives 2× vertical colour resolution for art.
- **Effects carry information where they can.** Pulse rate follows market activity; a thruster
  flickers only while `IN_TRANSIT`; a sale flashes the market it happened at. Idle motion
  (twinkle, atmosphere) is slow and low-contrast. Nothing blinks text.
- **The command line is line mode.** `cli.LineMode` already takes `out`/`err` `PrintStream`s;
  the console constructs one over a buffer and shows the result in a panel. Same commands,
  same tables, `run` included (todo §2 and §3 have this item).
- **Boot without a menu.** A token present means `Start`; `BootProgress` already exists and
  becomes an overlay. `New` stays `TradeyCLI register` in line mode.

## 5. Visual language

- **Palette.** One token set in `bridge/glyphs/Palette.kt`: background (near-black blue), panel
  border (dim), text (three greys), accent (the faction's colour; VOID is violet), and the three
  tones already in `Intent.Tone` (good, warn, neutral) plus a bad. Waypoint types and goods
  groups get hues from a fixed table, not `java.awt.Color` literals scattered about.
- **Frames.** Rounded box drawing, titles set into the top border, a one-cell gutter, no double
  borders. Selected panel: bright border and title. Focused row: accent background.
- **Type of data, type of glyph.** Gauges as `▁▂▃▄▅▆▇█` ramps; sparklines the same at one row;
  charts in braille for lines and half-blocks for bars; ages and ETAs as `▰▰▰▱▱` bars with the
  number beside them.
- **Art.** Procedural from a seed (the waypoint symbol), so a planet always looks the same:
  radius by type; surface texture scrolled sideways each frame (rotation); an atmosphere band
  in a lighter tint scrolled faster, offset a cell above the limb so it reads as a layer; a
  terminator shading the far side for depth. Asteroids: ragged shade polygons with a rare
  glint. Gas giants: horizontal bands with counter-scrolling speeds. Stations: symmetric block
  art with a blinking beacon. The jump gate: a ring whose filled fraction is the construction
  bill. Ships: 3 to 5 cell silhouettes by frame type, with role colour, a thruster cell that
  flickers in transit, and a fading trail along the route.
- **Space.** The map's background is a sparse starfield with slow twinkle and a faint orbit
  ring for each parent waypoint with orbitals.

## 6. Screens

Each is a `View`. Number keys or F-keys switch; the first is home.

1. **Bridge** (home). Header: agent, faction, phase, credits (counter tweens on change), next
   reset countdown, who is driving the plan. Then: phase progress (`Summary.progress`, the gate
   bill as a ring and a list), the credits chart (last hours plus the projection, braille),
   the fleet as cards (role glyph, name, behaviour and phase, fuel and cargo micro-gauges, ETA
   bar, where), market health per system, spent-on and earned-from bars (`Summary.spending`,
   `Summary.revenue`), plan intentions, the event feed (every `Event`, toned, replacing the
   five-item notifications), the API strip (requests, errors, throttled, the pacer's queue
   history as a sparkline), and the command line.
2. **System.** The map, top-down, zoom with `+`/`-` and the wheel, pan with arrows and later
   drag; waypoints as type glyphs with orbitals around parents; ships as sprites interpolated
   along `route.origin` → `route.destination` by `departureTime`/`arrival`, with the path drawn
   in braille and the remaining part brighter; hover or select shows a card. A list on the
   side, filterable by type and by "has market"/"has shipyard", click to select. Later: the
   45-degree view, same data, y scaled and depth-sorted.
3. **Waypoint.** The art, traits and modifiers, orbitals, ships present, and the market: imports,
   exports, exchange, supply and activity coloured, price sparklines from `market_prices`,
   when last read. Shipyard listings when there is one. Construction bill when it is the site.
4. **Ship.** Silhouette; frame, reactor, engine, modules, mounts as a parts list with glyphs;
   cargo bars by good; fuel gauge; condition and integrity; the current route as a timeline;
   the behaviour's phase history from `checkpoints`; its recent transactions.
5. **Markets.** The analysis screen. A goods × markets grid coloured by supply and activity;
   selected good's price history across markets as lines; routes ranked as `trades` does
   (`behaviour.decisions.Trading`); restricted exports and their starving imports.
6. **Economy.** Credits over the whole reset; ledger flows by purpose and source; contracts
   seen with payment against cost; the gate bill and cost to finish; `race` against other
   agents' banks, fleets and gate progress.
7. **Diagnostics.** API stats and queue pressure; recent errors and slow calls from
   `request_log`; behaviour failures with their restart timers; markets not read for hours;
   conflicts (two ships with the same claim or destination); the run lock and heartbeat; the
   tail of `log.txt`. This is where API errors live so they do not have to fit anywhere else.

## 7. Input

Keyboard: `1`–`7` or `F1`–`F7` screens; `Tab`/`Shift+Tab` focus; arrows, `PgUp`/`PgDn`,
`Home`/`End` inside a widget; `Enter` open; `Esc` back or close; `:` command line; `?` a help
overlay listing the bindings; `q` quits after a confirmation, `Ctrl+C` quits at once.

Mouse: left click focuses a panel, selects a row or a map object, opens on double click; wheel
scrolls lists and zooms the map; drag pans the map (milestone 7); right click opens a small
context menu with the object's verbs (`assign`, `unassign`, `market`, `jumpgate`) (milestone 7).
Hit testing is by rect, deepest widget wins, and a widget that does not handle an event lets it
bubble to its view and then to the global bindings.

## 8. Headless

`TradeyCLI bridge --frame <view> [--size 200x50] [--select <symbol>] [--ansi]` boots, renders
one frame of that view to stdout as plain text (or with colour under `--ansi`) and exits. With
`--sim` it renders from the simulator, so frames with moving ships and changing prices need no
network. This is how the look is iterated from a shell and how golden tests pin it. Unit tests
cover `FrameDiff` (minimal output, correctness against a naive repaint), `Layout`, braille and
half-block plotting, and each procedural renderer's determinism from its seed.

## 9. Build and run

`run.bat` builds the stable install, `build/install/TradeyCLI`, and opens the console from it;
`TradeyCLI run` is started from the same folder. Rebuilding into that folder while a `run` is
alive replaces the jars under it, so console development uses its own install folder and script:

```kotlin
// build.gradle.kts
val installBridge by tasks.registering(Sync::class) {
    description = "Installs the distribution to build/install-bridge, leaving a running `run` alone."
    with(distributions["main"].contents)
    into(layout.buildDirectory.dir("install-bridge/TradeyCLI"))
}
```

```bat
:: bridge.bat
CALL .\gradlew.bat installBridge
IF ERRORLEVEL 1 EXIT /B 1
CALL .\build\install-bridge\TradeyCLI\bin\TradeyCLI.bat bridge %*
```

Both installs share `profile/`, so the console sees the same store and plan the run is writing.
The console is a reader; the run lock still allows one `run` per agent.

`Main.kt` grows one branch: `bridge` as the first argument starts the console with the rest of
the arguments; anything else is line mode as today.

## 10. Milestones

Each leaves something usable. Sizes are relative.

**M0. Spike (a day).** Mordant on Windows Terminal: alternate screen, raw mode, size and
resize, left/right click coordinates and wheel, 24-bit colour, and a 200×50 starfield animating
at 20 frames a second through `FrameDiff` with synchronized output. Measure CPU. Go, or switch
the `Tty` adapter to JLine. Also confirm the synchronized-output and SGR-mouse behaviour of the
terminal Grant actually uses if it is not Windows Terminal.

**M1. Foundation (a few days).** `tty`, `canvas`, `scene` with Row/Column/Panel/Table/StatusBar,
the frame loop, the input router, the `bridge` subcommand, `installBridge`, `bridge.bat`,
`--frame`. Deliverable: bordered layout, live header from the snapshot, a fleet table you can
focus and click, quits cleanly, renders headless.

**M2. Bridge screen (a few days).** Every panel of the Kotter console re-drawn in the new
language: progress, credits chart in braille, fleet cards with gauges, health, ledger bars, plan,
event feed, API strip, command line over `LineMode`. Deliverable: parity with the old console
plus click and resize.

**M3. System map (a few days).** Top-down map with zoom and pan, glyph atlas, orbit rings,
selection by key and click, ships moving along their routes with trails, waypoint card.
Deliverable: watch the fleet fly. Kotter and `screen/` are removed after this.

**M4. Waypoint and ship (a week).** Procedural planet, asteroid, gas giant, station and gate
renderers with the rotating atmosphere; the waypoint screen with market and price sparklines;
the ship screen with silhouette, parts, cargo, route timeline and phase history.

**M5. Markets and economy (a week).** The goods grid, price history charts, ranked routes,
restricted-export warnings; the ledger, contracts, gate, and race screens.

**M6. Diagnostics (a few days).** `request_log`, failures and restart timers, stale markets,
conflict detection, run lock, log tail.

**M7. Depth and flair (open-ended).** The 45-degree system view with depth sorting; drag to
pan; right-click menus; goods icons; event effects (sale flash, purchase pulse, gate ring fill);
a thruster and docking animation; polish pass on the palette against real frames.

## 11. Decisions taken and assumptions

Answered by Grant on 2026-09-05:

1. **The terminal is a Windows PowerShell window with its default font.** So no assumption of
   Windows Terminal: synchronized output is emitted but not relied on, `bridge.bat` switches the
   console to UTF-8 (`chcp 65001`) and the start script pins the JVM's stdout encoding, and the
   spike screen shows every glyph family the console uses so a missing block (braille is the
   likely one in Consolas) is seen at once. Every dot plot can run in half blocks instead of
   braille; `d` toggles it on the spike screen.
2. **The console only watches.** It does not start `run` and has no command line. The plan is
   driven from another window with line mode; the console reads the store and the event stream.
   Section 4's command line and section 7's `:` binding are dropped.
3. **Flat 2D everywhere.** The 45-degree view is a switchable mode for later, not a design
   constraint; drag, right-click menus and the like are designed for the top-down map only.

Still assumed:

- Designed for 200×50, usable from 120×35; it degrades by dropping panels, not by wrapping.
- The console may spend API requests at boot (agent, fleet, status), as the dashboard does now,
  then follows the store and the event stream.
- The subcommand and package are called `bridge`.

## 12. Progress

- 2026-09-05: M0 and the skeleton of M1 in place. `bridge/tty` over Mordant 3.1.0, `bridge/canvas`
  (Surface, FrameDiff, Painter, Rect/Len, DotCanvas), `bridge/glyphs/Palette`, `bridge/fx/Starfield`,
  `bridge/views/SpikeView`, `Bridge.kt` with the frame loop and `--frame`. `installBridge` and
  `bridge.bat`. Tests in `src/test/kotlin/bridge/CanvasTest.kt` drive FrameDiff's output through
  a fake terminal and compare screens.
- 2026-09-05, Grant ran it in the PowerShell window: raw mode, mouse, every glyph row and resize
  all work, so Mordant stays. Measured 16 to 18 fps and 3 to 4 KB a frame at 120×34, 13 to 14 KB
  at 280×70. Causes and fixes: a timed input wait rounds up to Windows' 15 ms timer tick, so input
  moved to its own thread and the render thread sleeps then spins to the deadline; the differ now
  sends only the parts of a style that changed and hops along a row with a relative move; stars
  twinkle in four brightness steps instead of continuously. `TradeyCLI bridge --bench 200
  --no-boot --size 120x34` measures this headless: 1.7 KB a frame at 120×34 and 4.0 KB at
  280×70 for the spike screen, whose waves and trail redraw every frame on purpose; render plus
  diff under a millisecond.
- 2026-09-05, M1 landed. `bridge/scene`: `Widget` with a per-frame `Scene` for hit testing, `Table`
  (selection by key, scrolling, click, wheel), `Feed` (events with ages, wheel scrolls back);
  `WidgetView` routes Tab focus, keys to the focused widget and the mouse to the widget under it;
  `Shell` holds the views, a tab bar at the bottom (number keys, F-keys or a click switch), and
  the quit keys. `HomeView` is the first real screen: live header (agent, faction, bank, phase,
  who drives the plan), the fleet as a table, the selected ship's card (behaviour and phase,
  where it is or its route with an ETA gauge, fuel and hold gauges, inventory), the event feed.
  `--view NAME` picks the screen for `--frame` and `--bench`; `--sim --wait` renders a frame from
  the simulator with no network. Drag is not reported yet: mouse tracking asks for presses only;
  Mordant's button-motion mode is there for the map.
- 2026-09-05, M2 landed: the home screen has every panel of the Kotter console. Top row: the
  phase's progress (headline, the gate bill as text gauges per material, the summary's lines),
  the bank as a braille line with the trend's projection dotted in the warning colour and the
  range, rate and projected end beside it (`scene/CreditsChart`), market health per system as a
  table (focusable, so Tab now has somewhere to go). Middle: fleet, ship card, events. Bottom:
  the plan's intentions and chains (`scene/TextBlock`, word-wrapped), spent-on and earned-from as
  bars scaled to the largest (`scene/Bars`). The header shows the time to the next reset. Under
  36 body rows the bottom row is dropped, under 26 the top row too, so the fleet always fits.
  Checked with a live `--frame --wait` at 170×46 and a sim frame; the sim has no history or
  ledger, so its top and bottom panels show their empty states.
- 2026-09-05, the credits chart is anchored at zero with a round ceiling (1, 1.5, 2, 3, 4, 5, 6,
  8 × a power of ten), so its scale moves only when the bank crosses a round number; Grant found
  the self-scaling version restless.
- 2026-09-05, M3 landed: `views/SystemMap` and `views/SystemView`, screen 2. A camera maps world
  units to cells at two columns per row; `f` fits the system, arrows pan, `+`/`-` and the wheel
  zoom (about the pointer), `c` centres on the selection, `[`/`]` step through loaded systems.
  The glyph atlas (`glyphs/Atlas`) gives each waypoint type a glyph and hue and each ship role a
  colour; the star sits at the origin with a pulsing halo. Orbitals share their parent's
  coordinates in the game, so below 20 units a row they fold into the parent and above it they
  sit on a slowly turning braille ring. Parked ships are a count beside their waypoint; ships in
  flight are sprites interpolated along their route by departure and arrival time, with a fading
  trail behind and the road ahead dotted. Labels appear for notable waypoints when zoomed in and
  for everything when zoomed further, never overlapping. Clicking selects the nearest object,
  ships first. The side has the waypoint list (type, what it has, ships) and a card for the
  selected waypoint (traits, market read age, shipyard, ships here) or ship. Drag-to-pan and the
  isometric mode wait for M7. Parity with the Kotter console is reached; removing `screen/` and
  the Kotter dependency is Grant's call.
- 2026-09-05, after Grant's first look at the map: double-clicking a waypoint or ship, on the map
  or in the list (Enter does the same), centres the camera on it, which is how you find the gate.
  The star is drawn with a Geometric Shapes glyph (`◉`, the gas giant moved to `◍`) because the
  Dingbats `★` rendered wider than a cell in his font; its halo is four dim dots; it is clickable
  and its card explains what it is and sums the system up (waypoint counts by type, the jump gate
  and its state, markets, shipyards, minable rocks, factions, ships present). Glyph rule from
  this: stay inside Box Drawing, Block Elements, Geometric Shapes, Braille and Arrows.
- 2026-09-05, M4 landed: screens 3 and 4. `canvas/HalfCanvas` paints two colours per cell with
  `▀`/`▄`, which makes half-cells nearly square and spheres round. `fx/Noise` is seeded value
  noise; `fx/Art` draws every waypoint type: planets textured by trait (ocean, volcanic, frozen,
  jungle, swamp, temperate, radioactive, barren, rocky) and lit from the upper left, turning
  slowly, with clouds turning faster and an atmosphere glow past the limb whose colour follows the
  trait (breathable blue, toxic green, corrosive yellow); gas giants with differential rotation;
  cratered moons; tumbling asteroids with base lights; a station with beacons and walking dock
  lights; the gate as a ring lit to the construction fraction with a travelling spark; fuel
  stations; fields, nebulae and gravity wells. Rotation advances in half-pixel steps and colours
  are quantised to 32 levels a channel, so a 60×40 ocean planet costs about 4.5 KB a frame at
  20 fps rather than a full repaint each frame. `fx/ShipArt` has silhouettes per frame family,
  tinted by role, the hold filling with cargo, thrusters flickering in flight, scorch at low
  condition. `views/WaypointView`: portrait with trait chips, facts, the market as a table with
  price sparklines from `market_prices` (a new `AgentStore.listPrices` read, cached a minute in
  `BridgeModel.prices`), and the construction bill or our trades there. `views/ShipView`: the
  silhouette and behaviour line, parts with condition gauges, mounts and modules, route with a
  progress track, hold, trades. Left and Right step through the system or the fleet. Selection
  is shared through the model: a ship picked on the home or system screen is the one the ship
  screen shows; Enter or a double click on the fleet opens it.
- 2026-09-05, found while booting the console beside a live `run`: `AgentStore.archiveOlderResets`
  treated the current database's `-wal` and `-shm` files as older resets, moved the empty WAL
  and died on the locked shm. It now matches `data-YYYY-MM-DD.db` only, moves an older
  database's sidecars with it, and logs a move it cannot make instead of failing the boot.
  Line mode and the old dashboard were exposed to the same failure.
- 2026-09-05, after Grant's look: the planets, rocks and stations passed; the ships did not read.
  The first templates were top-down with the nose up, which at that size looks head-on. They are
  now side profiles, nose to the right, engines at the back: a lit spine, a shaded underside, the
  bridge windows forward, cargo sections that fill with the hold, hardpoints on the spine when
  the ship has mounts, and exhaust that streams left from the engines in flight, blue-white at the
  nozzle breaking up to orange. `--select SYMBOL` picks the ship or waypoint for `--frame`.
- 2026-09-05, the credits chart: buckets were a fixed 90 s, so a wide chart asked for more past
  than the engine held and left its left fifth empty. Buckets are now sized so the history held
  fills the width; the engine loads six hours of bank history instead of two (transactions stay
  at two); the projection is a fixed quarter hour, Grant's call: ninety minutes of a line that
  predicts nothing cost density for no information.
- 2026-09-05, M5 landed: screens 5 and 6. `scene/Grid` is a cursor grid with row and column
  labels, scrolling both ways; `scene/LineChart` draws several timestamped series on one braille
  canvas with a legend. `views/MarketsView`: every good against every market in the home system,
  cells showing the export's buy price or the import's sell price, coloured by supply (scarce red
  to abundant bright) with a red ground for restricted activity; the selected good's price
  history across the markets that trade it; the routes `Trading.rank` would give the biggest
  hauler now (recomputed every quarter minute, Enter on a route moves the grid to its good); and
  the starving producers with the inputs that would feed them and the cheapest healthy source
  (`knowledge.MarketHealth`). `views/EconomyView`: the bank over the whole reset (a second
  `CreditsChart` without projection, from a cached whole-history read), the race across the
  account's agents from their stores on disk (cached five minutes, ours from the snapshot), the
  ledger as totals and bars, every contract seen with payment, our cost and net from the store's
  records, and the gate: cost to finish at today's prices, rush verdict, per-material gauges with
  the cheapest price, and the summary's progress lines. Headless frames now paint twice with a
  two-second pause after boot so the cached reads show.
- 2026-09-05, Grant asked for the leaderboard and the galaxy before diagnostics: screen 7,
  `views/GalaxyView` over `bridge/Galaxy`. `ServerStatus` now carries the server's stats, health,
  leaderboards and announcements (all optional, so older stores still decode) and the snapshot
  exposes it. The galaxy fetches, on the background lane and cached: the status every five
  minutes; the public record of every agent on the leaderboards and the system each calls home
  (one request each, once); and on request only, because each costs hundreds of requests against
  the account's shared budget, the whole galaxy (`L`, paged into the store) and our rank among
  every agent (`R`). The map shows every known system as a star by type, our home and the
  leaderboard homes named with their rank; pan, zoom, click, and Enter or a double click opens a
  system whose waypoints are loaded on the system screen. The side has the most-credits board
  with our row appended when we are below it (with the gap to the last place, or our rank once
  `R` has run), the most-charts board, and the server's numbers, reset timer, market update age
  and announcements.
- 2026-09-05, Grant asked for lazy requests: nice-to-have reads should wait for spare capacity
  instead of competing with ships. `Priority.IDLE` in `api.RequestPacer`: an idle request only
  ever takes a static point when the static pool is full (so one point is always left for real
  work), never touches the burst pool, and only after 300 ms without a real grant. That gives idle
  work about one request a second when the account is quiet and nothing when it is busy. The
  pacer counts `idleGranted`. Everything the galaxy screen fetches is idle now, and it no longer
  waits to be asked: the first status starts the galaxy crawl (one request per twenty systems,
  into the store, so once per reset) and the ranking (refreshed every half hour); `R` re-ranks at
  once. The other lanes are unchanged; classifying the rest of the client's reads is for later.
  Pacers are per process and the limit is per account, so a `run` in another window is invisible
  to the console's pacer; a 429 is the one signal that crosses, and on one the idle lane stands
  down for thirty seconds (`RequestPacer.noteThrottled`, called by the client).
- 2026-09-06, the Kotter dashboard is retired at Grant's word: `screen/`, `notification/` and the
  Kotter dependency (and with it JLine) are gone, `WaypointType` no longer carries AWT colours,
  and `TradeyCLI` with no arguments is the console. `run.bat` builds the stable install and opens
  it; `bridge.bat` keeps building the development install beside it.
- 2026-09-06, the interface catches up with the scripting work done alongside it. Idle time
  (`behaviour.decisions.Idle` over the phase log the engine now keeps): the fleet table has an
  idle column and turns a row amber past half a day idle; the phase panel carries the fleet's
  idle line; a new home panel lists idle by behaviour, worst first; the ship screen has a time
  panel with the last day as a strip (green worked, amber waited), the busy and idle totals and
  what the ship waited on most, and its log now merges phase changes with trades. The money
  panels on the home screen cover the last day as the summary's do (`Summary.WINDOW`); the
  economy screen keeps the whole reset. Minable waypoints show their valid surveys and the goods
  they promise, and the system list says "ore surveyed". The galaxy screen has a neighbours
  panel: agents headquartered in our system with their faction, bank and fleet, and every agent
  whose ships appear in the transaction history of the markets we have read, from the same
  ranking pass that already pages every agent.
- 2026-09-06, Grant noticed the leaderboard homes trickling in and asked about connections. The
  homes were one idle request per agent behind the galaxy crawl in the same queue. Now the ranking
  pass runs first and is the only source of agents' records (it pages every agent anyway), the
  crawl waits for it, and what does not change within a reset is kept in the store: every public
  agent (`public_agents`) and every gate read (`gate_connections`), so a restart shows homes and
  connections at once. Connections come only from reading each gate (one request per gate; the
  API has no galaxy-wide graph), so `Galaxy.mapGates` reads them breadth first outward from home,
  each gate's answer naming the next systems to read, at most 120 a run, and the map draws them
  as dim lines with the home's in the accent and the selection's bright; a system's card lists
  what its gate connects to.
