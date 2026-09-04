# Plan

Order matters: each step is the floor the next one stands on. Details and reasoning are in
`docs/codebase-review-2026-09.md`, section 7.

## Done

- Build on current tooling (Gradle 9.7.1, Kotlin 2.4.10, Kotter 1.4.0).
- Boot menu that fails loudly instead of freezing; runtime folders created on startup.
- Registration with an account token; `Start` from an agent token.
- Lenient JSON and the model fixes needed to decode API 2.3 on the load path.
- Ship automation switched off (`GameState.scriptsEnabled`) so the dashboard can be exercised safely.

## 1. API layer

- [x] One typed client (`api.ApiClient`): bearer auth, `data`/`error` envelope handling,
      `ApiError` with the API's code and message, 15 s timeouts.
- [x] Account-wide request pacer (`api.RequestPacer`) implementing the documented pools (2 per
      second static, 30 per minute burst) with priority lanes: interactive, ship actions,
      background refresh. The limit is per account, so every agent of the account shares it.
- [x] Retry with backoff on 429 and 5xx, honouring `Retry-After`.
- [x] Paged reads (`/systems/{s}/waypoints`, `/my/ships`) instead of one request per entity.
- [x] Model drift test (`ModelDriftTest`) against `api-docs/live/openapi-bundled.json`: refresh
      the spec, run the build, read what moved. Generated models were considered and parked until
      the domain is reshaped in step 3: the generated classes are immutable and named differently,
      and the old script layer mutates models everywhere.
- [x] Boot path (`Start`, `New`) on the new client. The old `SpaceTradersClient` queue stays only
      for the parked scripts and goes with them.
- [ ] Typed bindings for the endpoints the old scripts never used (contracts, surveys, jump and
      warp, scan, mounts, construction), as the new behaviours need them.

## 2. Headless engine and line mode

- [x] `engine.Engine` owns all game state on one engine thread. Operations are `suspend`
      functions that run there and suspend on the API instead of blocking, so they interleave
      without locks; state goes out as a `StateFlow<Snapshot>`, happenings as a `SharedFlow<Event>`.
- [x] Line mode (`cli.LineMode`): `status`, `agent`, `ships`, `waypoints`, `markets`, `market`,
      `shipyards`, `repl`, with `--agent` and `--refresh`. Stdout for results, stderr for progress.
- [x] Dashboard is a reader: one snapshot per render, repaint on every state change, events become
      notifications. No `Timer`, `runBlocking` or write queue on the engine path; the ones left
      belong to the parked scripts and go with them.
- [ ] Console command line in the dashboard runs the same commands as line mode.

## 3. Scripting overhaul

Design: `docs/scripting-rewrite.md`. One language, three layers (verbs, behaviours, plan), one
simulator. Milestones from the design:

- [ ] Verbs and simulator, with a conformance test both implementations pass.
- [ ] First behaviour, `mineAndSell`: pure decisions, checkpoints, phase reporting, simulator tests.
- [ ] Plan file and supervisor; `assign`, `unassign`, `plan` in line mode and the console; resume
      from checkpoints; `scriptsEnabled` moves to the supervisor.
- [ ] Live trial: one drone for a day, watched through the request log.
- [ ] More behaviours: `probeMarkets`, `runContract`, `haul`, then the expansion policy.
- [ ] Retire the old layer: `script/`, `SpaceTradersClient`, `DbClient`, `FileWritingQueue`,
      `SavedScripts`, `PriceHistory`, the `GameState` facade, `database/`, and their tests.
- [ ] Revisit generated API models here, when the domain classes are being reshaped anyway.

## 4. Storage

- [x] Account token at the top, one folder per agent under `profile/agents/<SYMBOL>/` with its
      token and databases (`storage.Layout`). A token at the old location is moved on first start.
- [x] One SQLite database per agent and reset (`storage.AgentStore`): agent, ships, systems,
      waypoints, markets, shipyards, price history, transactions, checkpoints, request log. WAL,
      one writer thread, entities stored as the API's JSON plus indexed columns.
- [x] Reset detected from `GET /`; older resets archived on open.
- [ ] Write transactions from buy and sell responses once behaviours make them.
- [ ] Retire `database/` and the JSON caches under `profile/` together with the parked scripts.

## 5. TUI polish

- [ ] Terminal width from the terminal, not the 160-column constant.
- [ ] Real waypoint art, or drop the placeholder planet.
- [ ] Turn the remaining `println` calls into log lines; sane log level and rotation.
- [ ] Decide Kotter versus Mosaic with a one-day spike once the engine is stable.
