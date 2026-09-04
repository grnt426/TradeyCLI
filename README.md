# TradeyCLI

A terminal client and (eventually, again) bot for [SpaceTraders](https://spacetraders.io), the game
that is just an API. Kotlin, Kotter for the screen, SQLite for the bits worth keeping.

## Running it

You need a JDK (17 or newer to launch Gradle; the build fetches its own toolchain) and an agent token.

1. Register an agent at https://my.spacetraders.io and mint a token for it.
2. Paste the token into `profile/authtoken.secret`. The app creates the empty file on first run.
3. `run.bat`, or `gradlew installDist` and run `build/install/TradeyCLI/bin/TradeyCLI`.
4. Type `Start`.

Want the client to do the registering? Put an account token (account settings on the portal) in
`profile/accounttoken.secret`, set `name` and `faction` in `profile/profile.settings.json`, and type
`New` twice.

Esc quits. Everything the app has to say ends up in `log.txt`, so look there first when something
is off.

## Where things stand

The dashboard renders and talks to the API. Ship automation is switched off
(`GameState.scriptsEnabled`) until the scripting layer gets rebuilt. `todo.md` has the plan;
`docs/codebase-review-2026-09.md` has the reasons.

## Layout

- `api-docs/` - cached OpenAPI spec and wiki. `api-docs/refresh.ps1` re-pulls them.
- `src/main/kotlin/screen/` - the Kotter screens.
- `src/main/kotlin/model/` - game state and the API models.
- `src/main/kotlin/script/` - the old automation. Parked, not running.
- `profile/` - settings, tokens (git-ignored) and per-entity JSON caches.
- `database/` - SQLite, git-ignored.

Weekly server resets wipe the universe. Tokens die with it; mint a new one and `Start` again.
