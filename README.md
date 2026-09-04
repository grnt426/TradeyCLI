# TradeyCLI

A terminal client and (eventually, again) bot for [SpaceTraders](https://spacetraders.io), the game
that is just an API. Kotlin, Kotter for the screen, SQLite for the bits worth keeping.

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

## Line mode

Give it arguments and there is no dashboard, just an answer:

```
TradeyCLI status
TradeyCLI ships
TradeyCLI waypoints            # home system; or name one
TradeyCLI markets
TradeyCLI market X1-AB12-C3
TradeyCLI shipyards
TradeyCLI --agent OTHERGUY --refresh ships
TradeyCLI repl                 # reads commands from stdin until EOF
```

Progress goes to stderr, results to stdout, so it pipes. `--refresh` re-fetches instead of using
what is cached; `--agent` picks a folder under `profile/agents/`.

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
  printf "status\nships\nwaypoints\n" | TradeyCLI repl 2>/dev/null
  ```

  A blank line, `quit`, or end of input ends it. `--refresh` on a line re-fetches for that command.
- **It counts against the real rate limit.** Every command talks to the live API through the
  same per-account pacer as the dashboard, two requests a second. A `status` is two calls plus a
  fleet fetch; the first `waypoints` on a system is a few more, then it is served from the store.
  Do not loop it.
- **Everything ends up in the store.** `profile/agents/<SYMBOL>/data-<reset>.db` is plain SQLite;
  `sqlite3` or any browser opens it. `request_log` has one row per API attempt with status and
  duration, which is the first place to look when something was slow or throttled.

For tests that must not touch the network, go one layer down: `engine.Engine` takes a factory for
the API client and one for the store, so a test can hand it Ktor's `MockEngine` and a temp folder.
`src/test/kotlin/engine/EngineTest.kt` boots a whole engine that way and asserts on the snapshot
and on which paths were requested. `cli.LineMode` likewise takes an engine and streams, so its
output can be captured in a test without a process.

## Where things stand

The dashboard renders and talks to the API. Ship automation is switched off
(`GameState.scriptsEnabled`) until the scripting layer gets rebuilt. `todo.md` has the plan;
`docs/codebase-review-2026-09.md` has the reasons.

## Layout

- `api-docs/` - cached OpenAPI spec and wiki. `api-docs/refresh.ps1` re-pulls them.
- `src/main/kotlin/api/` - the paced, typed API client.
- `src/main/kotlin/engine/` - owns the game state; the screens and line mode read its snapshots.
- `src/main/kotlin/storage/` - one SQLite file per agent and server reset.
- `src/main/kotlin/cli/` - line mode.
- `src/main/kotlin/screen/` - the Kotter screens.
- `src/main/kotlin/model/` - the API models, plus `GameState`, a facade the old scripts still use.
- `src/main/kotlin/script/` - the old automation. Parked, not running.
- `profile/` - settings and the account token; `profile/agents/<SYMBOL>/` holds each agent's
  token and its `data-<reset>.db` (git-ignored). Older resets end up in `archive/`.
- `database/` - the old SQLite file; only the parked scripts and their tests still use it.

Weekly server resets wipe the universe. Tokens die with it; mint a new one and `Start` again.
