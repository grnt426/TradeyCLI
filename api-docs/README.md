# SpaceTraders API docs (local cache)

Local, version-controlled copy of the SpaceTraders reference material so the
client can be developed and searched offline, and so the code tracks the exact
API revision it was written against.

Do not edit files under `spec/`, `wiki/`, or `live/` by hand. Re-run
`refresh.ps1` to update them and commit the result.

## Layout

| Path | Source | What it is |
| --- | --- | --- |
| `spec/reference/SpaceTraders.json` | github.com/SpaceTradersAPI/api-docs (`main`) | OpenAPI 3.0.1 root document. Schemas are `$ref`s into `spec/models/`. |
| `spec/models/*.json` | same repo | One JSON Schema per API model (Ship, Market, Waypoint, ...). Enum lists live here. |
| `spec/README.md`, `spec/docs/overview.md` | same repo | Upstream getting-started text. |
| `wiki/*.md` | github.com/SpaceTradersAPI/api-docs/wiki | Community-maintained mechanics: rate limits, fuel and travel-time formulas, ship types, components, market import/export map, supply chain. |
| `live/openapi-bundled.json` | `GET https://api.spacetraders.io/v2/documentation/json` | The spec the running server publishes, fully bundled (no external refs). This is the authoritative one when it disagrees with `spec/`. |

## Provenance of this snapshot

| Item | Value |
| --- | --- |
| Snapshot taken | 2026-09-03 |
| api-docs commit | `45fbb04130aca3fa0bd9a634ab77b35fa6c468ab` (2025-04-28, "fix: add extra data to refuel and chart") |
| wiki commit | `7983d08c2e23c3537fd55fccdf46575690c5ccee` (2025-05-01) |
| Live server version | v2.3.0 (reset date 2026-08-30, weekly resets) |
| Spec `info.version` | 2.3.0 in both `spec/` and `live/` |

The GitHub spec lags the live server slightly. At snapshot time the live spec
had five operations the GitHub copy does not:

- `GET /error-codes`
- `GET /my/account`
- `GET /my/agent/events`
- `GET /my/factions`
- `GET /my/socket.io` (websocket departure events)

and four schemas not present as model files: `AgentEvent`, `ChartTransaction`,
`PublicAgent`, `SurveySize`. Prefer `live/openapi-bundled.json` for code
generation; use `spec/models/` when you want one readable file per model.

Files intentionally not copied from upstream: `.spectral.mjs`, `redocly.yaml`,
`.stoplight.json`, `.github/` (lint and publishing tooling for their repo).

## Refreshing

```powershell
.\api-docs\refresh.ps1
```

The script does a shallow clone of the two GitHub repos into a temp folder,
copies the files listed above, downloads the live bundled spec, rewrites the
provenance table in this README, and removes the temp folder. Review the diff
and commit.

## Useful entry points

- Rate limiting rules: `wiki/Ratelimit.md` (static pool 2 req/s, burst pool 30 req/60 s).
- Travel time, fuel, jump cooldown formulas: `wiki/Travel-Fuel-and-Time.md`.
- All trade goods: `spec/models/TradeSymbol.json`.
- Endpoint list: search `"operationId"` in `live/openapi-bundled.json`.
