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
