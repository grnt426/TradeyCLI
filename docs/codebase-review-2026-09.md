# TradeyCLI codebase review

Date: 2026-09-03. Branch: `claude/space-traders-review-ffb31f`.

Method: read every Kotlin source file, resolved the dependency graph with Gradle 8.13, and compared the model layer against the v2.3.0 OpenAPI spec now cached under `api-docs/`. The application was not run: it cannot currently be built (section 2).

## 1. Snapshot

| Item | Value |
| --- | --- |
| Size | 6,250 lines of Kotlin (about 4,900 main, 620 test, rest fixtures) across 135 files |
| History | 55 commits, 2024-01-14 to 2024-03-12, one author |
| Stack | Kotlin 1.9.0 (serialization plugin 1.9.21), Gradle 8.2 wrapper, JDK 17 toolchain, Ktor 2.3.7 CIO client, kotlinx.serialization, Exposed 0.47 + SQLite, log4j2 via kotlin-logging, Kotter 1.1.2-SNAPSHOT, JUnit 5 + MockK |
| API targeted | 2.1 / 2.2 (per commit messages and model shapes) |
| API live today | v2.3.0, weekly resets (last 2026-08-30, next 2026-09-06) |

## 2. It does not build today

> Update, later on 2026-09-03: items 1 and 2 below are resolved on this branch (Gradle 9.7.1, Kotlin 2.4.10, Kotter 1.4.0 from Maven Central, JUnit 6 launcher declared; four Kotlin 2 `noinline` fixes and the Kotter `Keys`/`GridCharacters` renames). `gradlew build installDist` is green, including the 33 tests after their fixed sleeps were replaced with a polling `awaitState` helper. Item 3 still applies.

Three independent reasons, in the order you will hit them.

1. **Kotter is unresolvable.** `build.gradle.kts` pulls `com.varabyte.kotter:kotter-jvm:1.1.2-SNAPSHOT` from `s01.oss.sonatype.org`, which now answers 404 (Sonatype retired OSSRH in 2025). Verified: `gradle dependencies --configuration runtimeClasspath` reports `kotter-jvm:1.1.2-SNAPSHOT FAILED`; every other dependency resolves. Fix: depend on the Maven Central release `1.1.2` (Feb 2024, the version the snapshot became; the `kotterx.grid` API you use shipped in it) or `1.4.0` (Jul 2026), and delete the snapshot repository block.
2. **The Gradle wrapper cannot run on this machine's JDKs.** The wrapper pins Gradle 8.2; the installed JDKs are Corretto 22 and OpenJDK 24. Gradle needs 8.8 or newer to run on Java 22 and 8.14 or newer on Java 24. Fix: bump the wrapper to 8.14+ (or 9.x) and Kotlin to 2.2.x while you are there. The JDK 17 toolchain with the Foojay resolver can stay.
3. **Once compiled, it cannot talk to the current API.** See 4.1. This one is the real work.

## 3. Architecture as built

**Entry and terminal layer** (`Main.kt`, `screen/`, about 1,100 lines). One Kotter `session` with a single `section` that re-renders whichever `Screen` is active (`BootScreen` or `RunningScreen`, the latter delegating to `Console`, `System`, or `Market` sub-screens). Input and key handling are global (`runUntilKeyPressed(ESC)`), routed through `AppState`/`RunningRenderContext` singletons. The console sub-screen draws an 8-column grid dashboard at a hard-coded width from `profile.settings.json` (160 columns); there is no terminal size detection.

**HTTP layer** (`client/SpaceTradersClient`). A singleton holding one Ktor client. Requests are closures pushed onto a `ConcurrentLinkedQueue` and drained by a `java.util.Timer` every 500 ms, one request per tick (so at most 2 requests per second and at most 1 in flight). Each job runs `runBlocking(Dispatchers.IO) { launch { withTimeout(2_000) { ... } } }`, parses `data` with a strict `Json`, and invokes a `callback`/`failback` pair. `callGet` is a separate blocking path used during boot.

**Model layer** (`model/`, about 60 files). kotlinx.serialization data classes mirroring API models, plus top-level functions that both mutate local state and enqueue API calls (`toOrbit`, `navigateTo`, `sellCargo`, `refreshMarket`). `GameState` is the god object: caches for agent, ships, waypoints, markets, shipyards; boot sequence; file IO; script resurrection.

**Scripting layer** (`script/`). A small DSL: `script { state(predicate) { body } }`. Each `ScriptExecutor` subclass declares an enum of states; `runForever(interval)` starts a `java.util.Timer` per script that, every tick, runs the body of the first state whose predicate is true. `changeState` persists the enum name to SQLite (`SavedScripts`). Reusable "modules" (`MiningModule`, `NavModule`, `SellModule`) inject groups of states into a script. Asynchronous results arrive through callbacks that set fields (`extractResult`, `navComplete`) read on the next tick from `AWAIT_*` states.

**Persistence** (`data/`). Three mechanisms side by side: one JSON file per entity under `profile/{agent,markets,waypoints,shipyards,systems}` (written directly in callbacks and also via `FileWritingQueue`, a Channel drained by a Timer); SQLite through Exposed for `SavedScripts` and `PriceHistory` (via `DbClient.writeQueue`, a `CopyOnWriteArrayList` drained by a 10 ms Timer); and `profile.settings.json` plus `authtoken.secret`.

**Other.** `notification/` is a five-item toast ring buffer whose entries hold Kotter `TextAnim` objects (the model depends on the UI library). `graph/` is a stub: a fixed pool of four threads whose task body is `TODO()`; it is never instantiated. `MessageableScriptExecutor` and `Script.data` are unused.

### Runtime threads

| Thread | Period | Purpose | Note |
| --- | --- | --- | --- |
| Kotter session | event driven | render, input | reads every shared map and list |
| `ApiRequestQueue` Timer | 500 ms | pop one request, run it with `runBlocking` | blocks the timer thread for up to 2 s per request |
| `dbwriter` Timer | 10 ms | open a transaction and drain `writeQueue` | opens about 100 empty transactions per second when idle |
| `FileWritingQueue` Timer | 50 ms | `runBlocking { channel.receive() }` | blocks forever when idle, so it is really a dedicated thread by accident |
| `initialLoading` Timer | 100 ms | poll two counters, then `sleep(2000)` | counters are plain `Int`s incremented from IO threads |
| `scriptRunnerForever` Timer, one per script | 1 to 30 s | evaluate states | all share one thread name; an exception in any state body kills that script's timer silently and the status stays `RUNNING` |
| Ktor CIO pool | | HTTP | callbacks mutate `GameState`, `ship.cargo.inventory`, notifications |

Nothing shared between these threads is synchronized. This confirms your instinct: with a 2 request per second budget, a single engine thread with one request pacer would remove almost all of this machinery.

## 4. Findings

### 4.1 API compatibility (blocking)

- **Strict JSON everywhere.** Every decode uses the default `Json`, so `ignoreUnknownKeys` is false and any field the server added since early 2024 throws. Against v2.3.0, concretely:
  - `System` gained `name` and `constellation`, so `GET /systems/{hq}` fails to decode during boot, `refreshSystem` returns null, and a normal start dies with `ProfileLoadingFailure("Failed to load 'Headquarters' data.")` before the dashboard appears.
  - `ShipFrame`, `ShipEngine`, and `ShipReactor` gained required `integrity` and `quality`; `ShipModule` gained `range`; `Chart` gained `waypointSymbol`. `GET /my/ships` and charted waypoints fail to decode; the client would start with zero ships.
  - `POST .../navigate` responses carry `events`; `POST .../extract` carries `events` and `modifiers`; `POST .../refuel` carries `cargo`; `POST .../transfer` carries `targetCargo`. These parse failures land in the `SerializationException` branch of `enqueueRequest`, which does not call `failback`, so scripts sit in their `AWAIT_*` states forever.
  - `Waypoint.modifiers` is typed `List<String>` but the API sends objects.

  One-line mitigation: a shared `Json { ignoreUnknownKeys = true; coerceInputValues = true }`. Real fix: regenerate models from the cached spec.
- **Registration no longer works as written.** `POST /register` now requires an account token (`AccountToken` security scheme, issued at my.spacetraders.io); the no-auth client in `BootManager.createAgent` will get 401, `createAgent` returns null, the exception is logged, and the app then moves to `RUNNING` with uninitialized `lateinit` state. The agent name is also hard-coded to `TripleHat12` and the faction to `VOID`, ignoring `profile.settings.json`.
- **Timeouts and rate limits.** Every request has a 2 s timeout; navigate, extract, and market reads regularly exceed that, and a timeout fires `failback` after the server has already applied the action, so local state diverges. A 429 is counted and the request is dropped; there is no backoff, no retry, and no use of the 30-request burst pool described in `api-docs/wiki/Ratelimit.md`. Most call sites pass `ignoredFailback`, so a dropped action is invisible.
- **Missing enum values.** Enums otherwise match the spec exactly, except `SHIP_BULK_FREIGHTER` (in `ShipType` and `TradeSymbol`) and `FRAME_BULK_FREIGHTER` (`TradeSymbol`). Any market or shipyard listing those fails to decode.

### 4.2 Logic defects

| Where | Defect | Effect |
| --- | --- | --- |
| `model/market/Market.kt:101` | `TransactionType.SELL -> TODO()` in `applyMarketTransactionUpdate` | Every successful sale throws `NotImplementedError`, which is an `Error`, not an `Exception`, so nothing in `enqueueRequest` catches it. It escapes `runBlocking`, kills the `ApiRequestQueue` timer thread, and the API queue stops for the rest of the session. |
| `model/market/Market.kt:51` | `orig.exports = market.imports` | Every market refresh overwrites exports with imports; trade-route logic is wrong after the first refresh. |
| `model/ship/components/Cargo.kt:33` | integer division in `hasCargoRatio` | Ratio is 0 or 1 only. Haulers looking for miners at 80% full only find 100% full ones. |
| `model/ship/components/Fuel.kt:11` | `Long` division in `hasfuelRatio` | "Fuel below 10%" is never true until the tank is empty. |
| `script/repo/modules/SellModule.kt:23` | `if (!cargoEmpty(ship)) changeState(afterSellState)` | Inverted. `SelfSellingMiningScript` never sells. `BasicHaulerScript.kt:231` has the correct form. |
| `script/repo/modules/SellModule.kt:66` | `navigateTo(ship, markets.keys.first())` on a map already known to be empty | `NoSuchElementException`; should be `market.symbol`. |
| `script/repo/pricing/PriceDiscoveryScript.kt:113` | `compareDist` is `(dx + dy)^2` | Not a distance; the "nearest" probe is often not nearest. |
| `data/PriceHistory.kt:44` | result of `transaction { }` discarded | `getHistoryForGood` always returns an empty list. |
| `model/GameState.kt:116` | `ships["agent.symbol${-2}"]` | Literal string key; never matches. The `PriceDiscoveryScript` on the next line is constructed but never executed, though its constructor writes a row to `SavedScripts`. |
| `screen/ConsoleSubScreen.kt:521` | `min(size - 1, commandHistoryIndex++)` | Post-increment inside the assignment; history never advances (the "Doesn't quite work" comment is accurate). |
| `screen/MarketSubScreen.kt:28` | chart data is `Random(0)` | The market chart is a mock; it never reads `PriceHistory`. |
| `model/GameState.kt:354` | `"$GAME_API/$params"` with a trailing slash already in `GAME_API` | Every URL is `v2//...`. Works today, fragile. |
| `model/ship/Ship.kt:77`, `:107` | `toOrbit`/`toDock` set local status before the server answers | On failure (ignored by default) local state is wrong and nothing refreshes ships periodically. |

### 4.3 Concurrency

- `GameState.ships/waypoints/markets` are `HashMap`s and `NotificationManager.notifications` is an `ArrayList`, mutated on Ktor and script threads and iterated on the render thread. `ConcurrentModificationException` during render is a matter of time.
- `ship.cargo.inventory` is edited by `jettisonCargo`, `removeLocalCargo`, transfer callbacks, and rendered concurrently.
- Boot completion is detected by comparing two non-atomic counters, then sleeping two seconds "to avoid race conditions".
- `FileWritingQueue.enqueue` is `suspend` on an unbuffered channel, so a market callback suspends until the file writer thread takes the item.
- `Script.runForever` has no exception boundary; a throwing state body kills the script's timer thread and the script reports `RUNNING` forever.

### 4.4 Scripting layer

The DSL is an enum-driven state machine evaluated by polling. It has two good ideas, resumability via persisted state and composable modules, and several problems that explain why it feels hard to navigate:

- Modules are wired by long positional enum lists (`addMiningModule(ship, MINING, MINING_COOLDOWN, AWAIT_MINING_RESPONSE, KEEP_VALUABLES, ...)`), so the transition graph exists only in the reader's head.
- Every asynchronous step needs an `AWAIT_*` state plus a callback flag plus a reset of that flag. About a third of all states are of this kind.
- Only the enum name is persisted. Targets, routes, and assigned markets are lost on restart, so each script's `INITIAL` state re-derives them heuristically. Only `PriceFetcherScript` and `PriceDiscoveryScript` are resurrected; the others are stubs in `fetchAllShips`.
- The management hierarchy (`StrategyScript` > `SystemOverseerScript` > `MiningForemanScript`/`GatewayConstruction`) is scaffolding with empty bodies.
- `scripts/basic_mining` (a textual `orbit / nav / dock / refuel / orbit / extract` list) is not read anywhere.

### 4.5 Persistence

Three stores, two write queues, two writer threads, and no single source of truth. `profile/` JSON is written in at least three places with different code paths. `SavedScripts` writes are asynchronous, so a crash after `changeState` can lose the transition. Nothing is keyed by reset date or agent, yet the game wipes the universe weekly.

### 4.6 Tests

Three test classes. The two script tests drive real `Timer` threads and assert after `sleep(5)` to `sleep(150)`, write to a real `database/unittests.db`, and mock global functions with MockK `mockkStatic`. The serialization tests use 2.1-era fixtures. Useful as documentation of intended transitions; not reliable as a safety net.

### 4.7 Hygiene

- `.idea/` is partly tracked, including `dataSources.xml` with absolute paths from a previous machine.
- `log4j-core` 2.19.0 is paired with `log4j-slf4j2-impl` 2.23.1; align them.
- 102 `println` calls remain alongside the logger; on Windows the Kotter render loop and stray `println`s fight over the same console.
- `run.bat` does `installDist` then runs the generated script. Fine, but there is no way to run headless.

## 5. Strengths worth keeping

- **Model coverage is broad and accurate for its date.** Every enum matches the spec except the bulk freighter entries. The fixture files under `src/test/resources` are real responses.
- **Resumable scripts** are the right instinct for a game with weekly resets and a client that will restart often. Keep the idea; change the representation.
- **Module composition** (mine, navigate, sell) is the right decomposition of ship behaviour; it just needs a better vehicle than positional enums.
- **`PriceHistory` schema** is a sensible design for the one dataset that has long-term value across a reset.
- **The dashboard concept** (system map, fleet status, command line, notifications, job pressure) is a good product idea and the ASCII rendering work is genuinely nice.
- **It is small.** Six thousand lines is a rewrite-friendly size. Nothing here is expensive to replace.

## 6. The terminal layer and Kotter

Facts first. Kotter went quiet from February 2024 (1.1.2) to November 2024, then shipped 1.2.0, 1.2.1 (Dec 2024), 1.3.0 (May 2026), and 1.4.0 (July 2026); the repository was pushed to in August 2026 and has two open issues. It is a one-maintainer library with a specific model: append-only "sections" that re-render in place, no alternate screen, no windowing, keyboard only. On Windows it needs a VT-capable console (Windows Terminal is fine); in a console without VT support it opens a Swing "virtual terminal" window, which will not exist in a headless run.

Most of the pain in `screen/` is not Kotter's fault: one global section renders everything, key handling is global, history is hand-rolled, width is a constant, and the model imports Kotter types. Any library dropped into this structure would inherit those problems.

**Verdict.** Kotter is viable and is not the thing to replace first. Recommended handling:

1. **Now:** switch to the Maven Central release (`kotter-jvm:1.1.2`, or `1.4.0` if you are willing to fix API drift). This restores the current UI within the hour.
2. **Before any TUI work:** make the engine headless. Define a narrow port: commands in, a stream of state and events out. Ship a line-mode front end first, `tradey status`, `tradey ships`, `tradey run mining --ship X1-RH52-...`, `tradey repl`, using Clikt for argument parsing and JLine 3 for the REPL (history, completion, and editing that actually work on Windows). This is the interface tests and Claude will drive; a full-screen TUI cannot be exercised from a non-TTY session with any library.
3. **Then the dashboard**, as a thin adapter over the same event stream. At that point the choice is cheap. Candidates: Kotter 1.4 (least migration, keeps the look you have), Mosaic (Jake Wharton's Compose-based TUI, actively maintained, the best fit for a declarative dashboard; verify raw-mode key input on Windows for the release you pick), or Lanterna 3.1 (mature Java, true full-screen with windows and panels, solid on Windows Terminal, verbose). Spend one day spiking your fleet panel in Kotter 1.4 and Mosaic and pick. Do not write a terminal layer from scratch; the value of this project is the engine.

## 7. Recommended sequence

Your plan was: CLI library, scripting, scheduling and throttling, storage, features. I would reorder so the engine is stable before anything renders it.

1. **Unbreak the build** (an hour): release Kotter, Gradle 8.14+, Kotlin 2.2, shared lenient `Json`, add the bulk freighter enum values, fix the `TODO()` in the sell path.
2. **API layer** (the biggest lever): generate models and endpoint bindings from `api-docs/live/openapi-bundled.json` (openapi-generator's `kotlin` generator with the `jvm-ktor` library) so every reset is a regenerate-and-diff rather than a hunt. Put one `ApiClient` behind a pacer implementing the documented pools (2 per second static, 30 per 60 s burst), with priority lanes (interactive commands, time-critical ship actions, background market refresh), retry with backoff on 429 and 5xx honouring `Retry-After`, and generous timeouts. Map `GET /error-codes` to typed errors.
3. **Engine loop**: one single-threaded coroutine dispatcher owns all game state. Commands enter through a channel; state changes leave as a flow. Add the headless runner and line-mode CLI here. Delete every `Timer`, every `runBlocking`, and the two write queues.
4. **Scripting**: ship behaviours as `suspend` functions on the engine dispatcher; `await` becomes `suspend`, which deletes every `AWAIT_*` state and callback flag. Persist a checkpoint record (behaviour type, entity, phase, parameters as JSON) at phase boundaries; a supervisor restarts behaviours from checkpoints. Keep the module idea as ordinary composable functions.
5. **Storage**: one SQLite file per agent and reset, written by one coroutine in WAL mode. Tables for agent, ships, waypoints, markets, market trade goods (history), shipyards, transactions, checkpoints, and a request log. Detect a reset from `GET /` and archive automatically.
6. **TUI**: see section 6.
7. **Features.**

## 8. What is now in `api-docs/`

`api-docs/README.md` records provenance and layout. In short: `spec/` is the GitHub repository (OpenAPI root plus one JSON Schema per model), `wiki/` is the community wiki (rate limits, travel formulas, ship types, components, market map), and `live/openapi-bundled.json` is the spec the running server publishes, which is five operations ahead of GitHub. `refresh.ps1` re-pulls all three.
