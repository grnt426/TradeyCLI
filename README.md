# TradeyCLI

A terminal client and bot for [SpaceTraders](https://spacetraders.io), the game that is just an
API. Kotlin, Kotter for the screen, SQLite for the bits worth keeping, and a simulator so the bot
can be tested without spending requests.

## Running it

You need a JDK (17 or newer to launch Gradle; the build fetches its own toolchain) and an agent token.

1. Register an agent at https://my.spacetraders.io and mint a token for it.
2. Paste the token into `profile/agents/<SYMBOL>/authtoken.secret` (make the folder; `SYMBOL` is
   the agent's callsign) and put that symbol in `name` in `profile/profile.settings.json`. A token
   left at the old spot, `profile/authtoken.secret`, gets moved into place on the first start.
3. `run.bat`, or `gradlew installDist` and run `build/install/TradeyCLI/bin/TradeyCLI`.
4. Type `Start`.

Want the client to do the registering? Put an account token (account settings on the portal) in
`profile/accounttoken.secret`, set `name` and `faction` in `profile/profile.settings.json`, and type
`New` twice.

Esc quits. Everything the app has to say ends up in `log.txt`, so look there first when something
is off.

## The dashboard and the bot

`TradeyCLI run` in a terminal does the work; the dashboard (no arguments) watches it. They share
the agent's SQLite store: the run writes every phase, sale, purchase and the bank after each
change, and the dashboard re-reads them every five seconds without spending a request. The
console screen shows the bank over the last hour with the trend projected ahead in yellow, an
"Intentions" panel (what each ship is doing, how the trading is going, what the fleet goal is
saving for and how far off it is) and the plan. `intentions` in line mode prints the same.

## Line mode

Give it arguments and there is no dashboard, just an answer:

```
TradeyCLI status
TradeyCLI ships                # with what each ship's behaviour is doing
TradeyCLI waypoints            # home system; or name one
TradeyCLI markets              # imports, exports, and when prices were last read
TradeyCLI market X1-AB12-C3
TradeyCLI shipyards
TradeyCLI asteroids            # every asteroid ranked by credits per hour for the mining ship
TradeyCLI trades               # buy-here-sell-there routes ranked by credits per hour weighted by market health (score)
TradeyCLI intentions           # what the bot is doing and saving for, and the credits trend
TradeyCLI contracts            # every contract seen: payment, our cost, dates
TradeyCLI gate                 # the construction bill, what we delivered and spent, cost to finish
TradeyCLI jumpgate             # a gate's connections; `jump SHIP GATE` takes a ship through
TradeyCLI catalog              # every ship listing and part for sale seen by any agent (no network)
TradeyCLI extractions          # everything mined this reset
TradeyCLI --agent OTHERGUY --refresh ships
TradeyCLI repl                 # reads commands from stdin until EOF
```

Progress goes to stderr, results to stdout, so it pipes. `--refresh` re-fetches instead of using
what is cached; `--agent` picks a folder under `profile/agents/`.

## Automation

Ships run *behaviours*, plain Kotlin `suspend` functions under `src/main/kotlin/behaviour/`. Which
ship runs which is the *plan*, `profile/agents/<SYMBOL>/plan.json`, edited from line mode:

```
TradeyCLI behaviours                                   # what exists and what it takes
TradeyCLI assign TRIPLEHAT-2 probeMarkets              # read every market's prices, then stop
TradeyCLI assign TRIPLEHAT-1 trade                     # haul goods between markets, best route every load
TradeyCLI assign TRIPLEHAT-1 mineAndSell --asteroid X1-TH77-B9 --market X1-TH77-B7 --surveys no
TradeyCLI goal fleet LIGHT_SHUTTLE 2 --reserve 150000  # buy shuttles while the bank stays above the reserve
TradeyCLI assign TRIPLEHAT-2 expand                    # the probe parks at the yard and buys them the moment the bank allows
TradeyCLI plan                                         # the plan and its goals, with anything wrong with it
TradeyCLI unassign TRIPLEHAT-1
TradeyCLI assign TRIPLEHAT-4 runContract               # negotiate, procure, deliver, fulfil, repeat
TradeyCLI assign TRIPLEHAT-1 supplyGate --site X1-TH77-I54   # haul the gate's materials; nurses a short producer's inputs instead of buying through it (--nurse off to buy blindly)
TradeyCLI chain add chips --leg COPPER:X1-TH77-H50/X1-TH77-A3 --ships TRIPLEHAT-3   # worker bees on a chain
TradeyCLI chain                                        # the chains' ledgers and the damped release policy's verdicts
TradeyCLI run --for 2h                                 # run the plan, printing every phase, then summarise
TradeyCLI buy MINING_DRONE X1-TH77-H51                 # a ship of yours must be at the shipyard
```

Only one `run` may drive an agent at a time: it holds `profile/agents/<SYMBOL>/run.lock` with a
heartbeat, a second `run` refuses, and the dashboard's Intentions panel says who is driving.

Rebuilding while a `run` is alive replaces the jars under it; the next class it has not loaded
yet fails, usually when it finishes or stops. Stop runs before `installDist`, then start them again.

Several agents on one account: `TradeyCLI register SYMBOL FACTION` (account token in
`profile/accounttoken.secret`), then `--agent SYMBOL` on any command; each agent has its own plan,
store and run.

`run` prints one line per phase change, extraction, sale and refuel to stderr and a summary table
to stdout when the time is up or every behaviour has finished. `ships` shows the current phase of
each ship while it runs. A behaviour that throws is restarted with backoff and the reason is
printed; one that finishes (a probe with nothing left to read) is left alone.

`docs/scripting-rewrite.md` is the design and the strategy the decisions encode.
`docs/market-mechanics.md` is what we know about how markets move and which knob in
`knowledge/MarketAssumptions.kt` carries each rule; `gate` prints each producer's health and the inputs to feed it.

## The simulator

`sim` runs the plan against a copy of the agent's system on virtual time: a day takes under a
second and nothing touches the network. It is seeded from the agent's store (boot once first) or
from a file.

```
TradeyCLI sim --hours 24                               # the current plan, or the default one
TradeyCLI sim --hours 24 --buy MINING_DRONE            # buy first, put the new ship to work: does it pay back?
TradeyCLI sim --hours 6 --trace                        # every phase change with its virtual time
TradeyCLI sim --seed src/test/resources/x1-th77-seed.json
```

The report has credits by hour, sales by good, API calls per hour against the budget, what state
each asteroid ended in, and where each ship was. Every rule the game does not document is a knob
in `src/main/kotlin/sim/SimRules.kt`, each marked observed or guessed; prices at markets no ship
has visited come from `knowledge/DefaultPrices.kt` and are marked `~` in the ranking. The store's
`extractions`, `transactions` and `market_prices` tables are what corrects the guesses after a live
run.

`--sim` on any other command runs it against the same simulated system behind a fake HTTP server,
at 60 times real speed by default (`--sim=10` for ten): `TradeyCLI --sim run --for 3h` watches the
whole client, request pacer included, work a plan in three real minutes.

## Testing without the screen

Line mode exists so the client can be exercised from anything that has a shell and no TTY: a
script, a CI job, or an assistant driving a terminal. The rules it plays by:

- **Run it from the repo root.** Paths are relative: `profile/` for settings and tokens,
  `log.txt` for the log. `gradlew installDist` first, then
  `build/install/TradeyCLI/bin/TradeyCLI` (`.bat` on Windows).
- **stdout is data, stderr is commentary.** Tables go to stdout; boot progress, prompts and errors
  go to stderr. `2>/dev/null` (or `2>$null`) leaves only the answer.
- **Exit codes mean something.** `0` worked, `1` bad usage, `2` boot failed (no token, token
  rejected, server unreachable). The failure reason is on stderr.
- **Tables are stable.** Header row, dashed rule, one row per item, columns padded with two spaces
  between, `(none)` when empty. Safe to grep and split on whitespace runs.
- **`repl` takes a script on stdin** and boots once for all of it:

  ```
  printf "status\nships\nasteroids\n" | TradeyCLI repl 2>/dev/null
  ```

  A blank line, `quit`, or end of input ends it. `--refresh` on a line re-fetches for that command.
- **It counts against the real rate limit.** Every command talks to the live API through the
  same per-account pacer as the dashboard, two requests a second. A `status` is two calls plus a
  fleet fetch; the first `waypoints` on a system is a few more, then it is served from the store.
  Do not loop it; use `sim` or `--sim` for anything repetitive.
- **Everything ends up in the store.** `profile/agents/<SYMBOL>/data-<reset>.db` is plain SQLite;
  `sqlite3` or any browser opens it. `request_log` has one row per API attempt with status and
  duration, which is the first place to look when something was slow or throttled.

For tests that must not touch the network, go one layer down: `engine.Engine` takes a factory for
the API client and one for the store, so a test can hand it Ktor's `MockEngine` and a temp folder.
`src/test/kotlin/engine/EngineTest.kt` boots a whole engine that way. Behaviour tests use
`sim.SimRun` on virtual time against `src/test/resources/x1-th77-seed.json`, a real home system;
`sim.VerbConformanceTest` runs the verbs both straight into the simulator and through the fake
server and the real client, so the two cannot drift apart.

## Where things stand

The dashboard renders and talks to the API; line mode runs behaviours and the simulator. The
strategy is survey (the probe reads every market's prices), then trade (buy exports, sell to
importers, best route by credits per hour every load, loads sized to the observed price impact),
expanding the fleet from the profits; mining is ranked too but pays an order of magnitude less
in this reset.
`todo.md` has the plan; `docs/scripting-rewrite.md` the design;
`docs/codebase-review-2026-09.md` the reasons.

## Layout

- `api-docs/` - cached OpenAPI spec, error codes and wiki. `api-docs/refresh.ps1` re-pulls them.
- `src/main/kotlin/api/` - the paced, typed API client, and `GameApi`, the interface the simulator also implements.
- `src/main/kotlin/engine/` - owns the game state and the verbs; the screens and line mode read its snapshots.
- `src/main/kotlin/behaviour/` - the behaviours and, under `decisions/`, the pure functions they decide with.
- `src/main/kotlin/plan/` - the plan file and the supervisor that runs it.
- `src/main/kotlin/sim/` - the simulator: rules, seed, virtual clock, fake server, runner.
- `src/main/kotlin/knowledge/` - what the game does not tell us: deposit traits to goods, price guesses.
- `src/main/kotlin/storage/` - one SQLite file per agent and server reset.
- `src/main/kotlin/cli/` - line mode.
- `src/main/kotlin/screen/` - the Kotter screens.
- `src/main/kotlin/model/` - the API models.
- `profile/` - settings and the account token; `profile/agents/<SYMBOL>/` holds each agent's
  token, `plan.json` and its `data-<reset>.db` (git-ignored). Older resets end up in `archive/`.

Weekly server resets wipe the universe. Tokens die with it; mint a new one and `Start` again.
