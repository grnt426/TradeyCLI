# Script rewrite

Status: design, agreed 2026-09-04. Replaces `src/main/kotlin/script/` (the enum state machines
polled by timers) once the milestones below land. Step 3 of `todo.md`.

## Goals, in priority order

1. **Ease of validation.** A behaviour must be checkable before it touches the live API, which
   costs real requests from a shared budget of two per second.
2. **Automated testing.** Ship behaviours run against a simulated universe on virtual time; the
   decisions inside them are pure functions tested with no engine at all.
3. **Rapid prototyping.** Write a behaviour, run it in the simulator, read its phase trace, fix.
   The rebuild-and-test loop is the editing loop.
4. **Legibility.** A behaviour reads top to bottom as the thing it does. What the whole empire is
   doing fits on one screen.

Runtime editing of behaviour code is not a goal. The plan (who does what) is editable at runtime;
the behaviours are compiled Kotlin.

## What the game asks of a script

From `api-docs/` (spec and wiki):

- Every action is a request that either fails or starts a wait. Navigate returns an arrival time
  minutes away; extract, survey, siphon and scan put the ship on a cooldown.
- Preconditions everywhere. Dock for refuel, buy, sell, deliver, repair and mount work; orbit for
  navigate, extract, siphon, jump and warp. Refuel and prices need a market; repair and mounts need
  a shipyard.
- Surveys expire, have sizes, and must be sent back verbatim. Components wear and need repairs.
- One contract at a time, with a deadline. Negotiating needs a ship at a faction waypoint.
- Markets drift (imports rise, exports fall) and prices are only visible with a ship present.
- Jump buys antimatter at the local market; warp needs a warp drive.
- The universe resets weekly. Nothing learned is worth much for long.

So a ship script is a long-running, mostly sequential workflow full of waiting, with a handful of
decisions inside it. The old design spent about a third of its states waiting; the new one makes
waiting a `suspend`.

## Options considered

| Option | Buys | Costs | Verdict |
| --- | --- | --- | --- |
| Embedded language (Lua via LuaJ, JavaScript via GraalJS) | Editing behaviours while the client runs | Second type system, an RPC surface kept in step with the engine, second test harness, errors and debugging across a language boundary | No. In an agentic workflow the rebuild is the edit loop, so the one benefit is gone and every cost remains. |
| Kotlin script files (`.kts`) | Same language, hot-ish reload | Slow compilation, weak tooling, still a boundary to the engine | No. |
| Free-form Kotlin (what we had) | Everything typed | Transition graph lived in positional enum lists and callback flags; unreadable | No, but keep the language. |
| Behaviour trees or GOAP in data | Whole plan visible; standard game-AI tooling | Every new leaf is still Kotlin; the tree syntax hides sequencing that plain code shows better | Partly: the *plan* is data, the *behaviours* are code. |
| **Kotlin in three layers plus a simulator** (below) | One language, one test runner, resumable programs for free from `suspend` | Behaviour changes need a rebuild | **Yes.** |

Kotlin needs no separate AST or interpreter: a `suspend` function is already a resumable program
the compiler builds for you.

## Architecture

```
plan (data)  ->  behaviours (Kotlin suspend functions)  ->  verbs (engine-owned)  ->  API
                        |                                          |
                  pure decisions                          simulator (same interface)
```

### Verbs

A small typed vocabulary owned by the engine. Each verb performs the call, updates the world and
the store, and waits out whatever the game imposes before returning. Verbs are the only code that
knows about the API, and the only code that touches ship state.

```kotlin
interface Verbs {
    suspend fun orbit(ship: String)
    suspend fun dock(ship: String)
    suspend fun navigateTo(ship: String, waypoint: String, mode: FlightMode = CRUISE)   // returns on arrival
    suspend fun refuel(ship: String)
    suspend fun extract(ship: String, survey: Survey? = null): Extraction               // returns after cooldown
    suspend fun survey(ship: String): List<Survey>
    suspend fun sell(ship: String, good: TradeSymbol, units: Int): Transaction
    suspend fun buy(ship: String, good: TradeSymbol, units: Int): Transaction
    suspend fun jettison(ship: String, good: TradeSymbol, units: Int)
    suspend fun deliver(ship: String, contract: String, good: TradeSymbol, units: Int)
    suspend fun jump(ship: String, waypoint: String)
    suspend fun repair(ship: String)
    suspend fun refreshMarket(waypoint: String): Market
}
```

Rules: a verb never guesses. `navigateTo` orbits first if docked; `sell` splits by the market's
trade volume; every verb re-reads the ship from the response so local state is never optimistic
(the old `toOrbit` set the status before the server answered). Failures are `ApiError`s with the
server's code; the verb layer maps the codes worth handling (ship already docked, cooldown, not
enough fuel) into typed exceptions the behaviour can catch.

### Behaviours

Plain `suspend` functions composed of verbs with ordinary loops and ifs, each about a page:

```kotlin
suspend fun BehaviourScope.mineAndSell(ship: String, asteroid: String, market: String) {
    while (true) {
        phase("travel to asteroid") { navigateTo(ship, asteroid) }
        phase("extract") { while (!cargoFull(ship)) extract(ship) }
        phase("jettison junk") { jettison(ship, worthless(cargoOf(ship))) }
        phase("travel to market") { navigateTo(ship, market); dock(ship); refuel(ship) }
        phase("sell") { sellAll(ship) }
    }
}
```

`BehaviourScope` gives a behaviour its verbs, a read-only snapshot, and `phase(name) { }`, which
does three things: publishes the phase into the snapshot so `ships` shows
`mineAndSell: extract 28/40`, writes a checkpoint (behaviour, ship, phase, parameters) to the
store at each boundary, and wraps the block so an unexpected failure stops this behaviour with a
readable reason instead of killing a thread.

Decisions are pure functions over a snapshot and unit-tested without any engine:

```kotlin
fun bestMarketFor(cargo: Cargo, from: Waypoint, snapshot: Snapshot): Market?
fun worthless(cargo: Cargo): List<Inventory>
fun surveyWorthUsing(survey: Survey, wanted: Set<TradeSymbol>, now: Instant): Boolean
```

A behaviour that needs a decision calls the function; it never contains the heuristic itself.

### The plan

The only runtime-editable piece: which ships run which behaviour with which parameters, plus
empire-level goals. Data, in `profile/agents/<SYMBOL>/plan.json`, validated against the world
before it is applied (the market exists, the ship carries a mining laser, the asteroid is in the
same system).

```json
{
  "assignments": [
    { "ship": "TRIPLEHAT-2", "behaviour": "mineAndSell", "params": { "asteroid": "X1-TH77-B6", "market": "X1-TH77-A1" } },
    { "ship": "TRIPLEHAT-3", "behaviour": "probeMarkets", "params": { "system": "X1-TH77" } }
  ],
  "goals": { "credits": 500000 }
}
```

Line mode edits it: `assign TRIPLEHAT-2 mineAndSell --asteroid X1-TH77-B6 --market X1-TH77-A1`,
`unassign TRIPLEHAT-2`, `plan` to print it. The dashboard shows the same table. Empire-level
policy (buy the next drone when credits allow, take a contract when one pays) is itself a
behaviour that edits the plan, added last.

### Supervisor

Owns running behaviours. On boot it reads the plan and the checkpoints, starts one coroutine per
assignment on the engine scope, and resumes each from its last phase. It restarts a behaviour that
throws, with backoff, and surfaces the reason as an event. It stops a behaviour when its
assignment is removed. Behaviours never start other behaviours; they edit the plan and let the
supervisor react. `scriptsEnabled` becomes the supervisor's on-off switch.

### The simulator

A fake universe behind the same `Verbs` interface, on virtual time (`kotlinx-coroutines-test`).
It models travel time and fuel from the wiki formulas, cooldowns, cargo capacity, extraction
yields from waypoint traits, market prices that drift and react to trades, refuel and repair
costs, and the rate limit. It is seeded from a real snapshot (the store) so tests run against the
actual home system.

A test drives a behaviour for ten fill-and-sell cycles in milliseconds and asserts on credits,
phase trace, and the number of verb calls. The simulator is also what the dashboard can run
against for UI work without spending requests.

## Observability

- Snapshot carries, per ship, the behaviour name, current phase, and a short status line.
- Every phase change is an event; the dashboard turns the interesting ones into notifications
  and line mode's `ships` and `plan` show the rest.
- `explain SHIP` prints the last decisions a behaviour made, with the inputs it used.
- The request log in the store already records every API attempt.

## Milestones

Each lands green and behind `scriptsEnabled` until the last one.

1. **Verbs and simulator.** The interface above, the live implementation over `SpaceTradersApi`,
   the simulator, and a conformance test both implementations pass (dock when docked, navigate
   consumes fuel, extract respects cooldown).
2. **First behaviour.** `mineAndSell` with its pure decisions, checkpoints, phase reporting, and
   simulator tests. `ships` shows phases.
3. **Plan and supervisor.** `plan.json`, `assign`, `unassign`, `plan` in line mode and the
   console; resume from checkpoints after a restart; `scriptsEnabled` moves to the supervisor.
4. **Live trial.** One drone on `mineAndSell` for a day, watched through the request log.
5. **More behaviours.** `probeMarkets` (price discovery), `runContract`, `haul`; then the
   expansion policy behaviour.
6. **Retire the old layer.** Delete `script/`, `SpaceTradersClient`, `DbClient`,
   `FileWritingQueue`, `SavedScripts`, `PriceHistory`, the `GameState` facade and the `database/`
   folder, with their tests.

## Open questions

- Flight mode policy: `CRUISE` everywhere until fuel economics are modelled in the simulator.
- Whether surveys are worth the cooldown for a lone drone; decide with the simulator, not by hand.
- Multi-agent: the plan is per agent, the pacer is per account. Cross-agent coordination waits
  until there is more than one agent.
