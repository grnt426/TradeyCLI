# Plan

Order matters: each step is the floor the next one stands on. Details and reasoning are in
`docs/codebase-review-2026-09.md`, section 7.

## Done

- Build on current tooling (Gradle 9.7.1, Kotlin 2.4.10, Kotter 1.4.0).
- Boot menu that fails loudly instead of freezing; runtime folders created on startup.
- Registration with an account token; `Start` from an agent token.
- Lenient JSON and the model fixes needed to decode API 2.3 on the load path.
- Ship automation switched off (`GameState.scriptsEnabled`) so the dashboard can be exercised safely.

## 1. API layer (in progress)

- [ ] One typed client: bearer auth, `data`/`error` envelope handling, `ApiError` with the API's code
      and message, generous timeouts.
- [ ] Account-wide request pacer implementing the documented pools (2 per second static, 30 per
      minute burst) with priority lanes: interactive, ship actions, background refresh. The limit
      is per account, so every agent of the account shares it.
- [ ] Retry with backoff on 429 and 5xx, honouring `Retry-After`.
- [ ] Paged reads (`/systems/{s}/waypoints`, `/my/ships`) instead of one request per entity.
- [ ] Model drift test against `api-docs/live/openapi-bundled.json` so a spec refresh shows
      exactly which fields and enum values moved. Generated models were considered; parked until
      the domain is reshaped in step 3, because the generated classes are immutable and named
      differently, and the old script layer mutates models everywhere.
- [ ] Boot path (`Start`, `New`) on the new client; the old `SpaceTradersClient` queue stays only
      for the parked scripts.

## 2. Headless engine and line mode

- [ ] Single-threaded engine loop that owns all game state: commands in through a channel, state
      changes out as a flow.
- [ ] Line-mode commands runnable without the TUI (`status`, `ships`, `waypoints`,
      `market <symbol>`), so the client can be driven from a plain terminal and from tests.
- [ ] Dashboard becomes a reader of engine state; no `Timer`, no `runBlocking`, no write queues.

## 3. Scripting overhaul

- [ ] Ship behaviours as `suspend` functions on the engine loop; `await` replaces every
      `AWAIT_*` state and callback flag.
- [ ] Checkpoints (behaviour, entity, phase, parameters) persisted at phase boundaries; a
      supervisor resumes them after restart.
- [ ] Re-enable automation one behaviour at a time, starting read-only (price fetching).
- [ ] Revisit generated API models here, when the domain classes are being reshaped anyway.

## 4. Storage

- [ ] Account token at the top, one folder per agent (settings, token, caches).
- [ ] One SQLite database per agent and reset: agent, ships, waypoints, markets, price history,
      shipyards, transactions, checkpoints, request log. Single writer, WAL mode.
- [ ] Detect a server reset from `GET /` and archive automatically.

## 5. TUI polish

- [ ] Terminal width from the terminal, not the 160-column constant.
- [ ] Real waypoint art, or drop the placeholder planet.
- [ ] Turn the remaining `println` calls into log lines; sane log level and rotation.
- [ ] Decide Kotter versus Mosaic with a one-day spike once the engine is stable.
