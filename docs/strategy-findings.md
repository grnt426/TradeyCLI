# Strategic findings

What we have measured about SpaceTraders' economy and how the phases play, condensed for
another session to pick up. Details and knobs live in the linked docs; numbers are from the reset
of 2026-09-06 (agent TRIPLEHAT, faction ECHO, home X1-ZJ35) unless said otherwise.

## The economy in eight facts

1. **Markets are per waypoint, prices are visible only with a ship present.** Exports are produced
   over time and go RESTRICTED when their imports are unmet; imports are consumed and recover over
   about six hours; exchange goods move only when agents trade them. An empty read must not
   overwrite prices. See [market-mechanics.md](market-mechanics.md).
2. **A fresh system's spreads pay enormously and die fast.** The frigate made 909k in its first hour
   at home and about 120k an hour after eight hours with five traders on the same markets. By hour
   eight the whole home system had two routes above a 3% margin. Income comes from new markets,
   not more hulls on old ones.
3. **Feeding producers raises output.** F55's fab-mats went WEAK to STRONG and its trade volume from
   20 to 43 after two haulers fed its iron and quartz for four hours. A consumer stops paying at
   HIGH and buries at ABUNDANT (we sold microprocessors at a third of cost that way). Feed to HIGH,
   never past it.
4. **Charts are the boom's income.** Average 15.6k per waypoint (asteroids 10k, planets and moons
   30k to 47k), 50 to 60 waypoints a system, so about 0.8M a system for a 22k probe. 563 charts
   made 16.7M in the gate's first six hours. A waypoint charted by someone else refuses with 400;
   the cache must be cleared or the probe retries forever (5,800 wasted requests an hour once).
5. **Contracts: one at a time, offered or accepted, 24 hours to accept.** 71 contracts netted 4.75M
   in 20 hauler-hours (236k an hour); the small ones (33 under 20k) cost 9 hours for 269k but
   skipping one blocks the stream for a day. Accept everything, clear the small ones fast. Goods
   cost is not recorded yet.
6. **Yard prices climb with every purchase there and fall back over time.** A2's probes went 23k to
   128k during a 33-probe binge while C44 in the same system asked 21k. Buy at the cheapest known
   yard; charting reads every yard it passes.
7. **The API budget is the ceiling on fleet size.** About 2.5 requests a second per account, shared
   with the bridge and any CLI query. Measured worth per request: charting about 5,000 credits,
   trading about 1,000, market and yard reads 0. Idle ships reading prices on a timer were a third
   of the budget at 130 ships; they now park. A trade cycle costs the same requests whatever the
   hold, so under the budget bigger holds win: a heavy freighter (225, speed 36) is about six
   times a light hauler (80, speed 15) per request.
8. **The gate network is a third of the galaxy.** 3,096 of 7,026 systems have a gate; the other
   3,930 are reachable only by warp (Explorer, 723k, warp drive I, tank 800: about one gate-less
   system an hour) and nobody trading through gates has touched them. Jumps are instantaneous with
   a cooldown of 15 + 0.3 x distance seconds and a fee; a jump needs built gates at both ends.

## Other players

The leaderboard samples (`leaders`) say fleet size is the whole game after the gate: the top
agents bought 200 ships in three hours and earned 7M to 14M an hour with 270 to 700 ships; we
were 5th at 3.5M an hour with 77. Per ship we were the most efficient, which means growth rate
and request budget are the constraints, not per-ship behaviour. Some players escaped hours ahead
of us; gates from neighbours' systems were already built when ours opened.

## The phases

Encoded as `Plan.phase` and `knowledge/Strategy.kt`; see [phases.md](phases.md) and
[boom.md](boom.md).

**Escape** (register to home gate complete): 23 h 25 min this reset, 15 ships and 3.4M at the
end. What worked, in order of value: the probe charts home first (fastest early money), the
frigate trades fresh spreads, one hauler supplies the gate at the producers' healthy take rate,
one runs contracts, traders feed the gate chains' short inputs as ordinary loads, two feeder
haulers work the seeded gate chains, a LIMITED chain export goes only to another gate producer.
With 100 units left buy regardless of health; with an hour left buy the boom's probes and park
them at the gate. Money stops being the constraint early; the producers' output is the clock.
Drones and shuttles were not worth it.

**Boom** (gate open): pioneers take frontier gates, read the far gate for more, chart, and each
opened system gets a kit of two probes bought at the nearest cheap yard and routed hop by hop
(multi-hop routing over the gates we have read, persisted). Four chart probes per system, spare
probes park, home's spare traders spread two per settled system, three heavy freighters trade
where traders are thinnest, two explorers warp to gate-less systems. Six hours in: 39 systems,
150 ships, 31M, about 3M an hour, mostly charts.

**Late** (not designed): the boom ends when the frontier and the gate-less systems within reach
are charted. Trading in many fresh systems with big holds under the request budget is the likely
shape; a settle-and-release of surplus haulers and a per-system idle measure are still unbuilt.

## Mistakes worth not repeating

- Kits counted only ships already in the system: 33 probes bought for one system. Count ships
  bound for it too, and retire a kit goal once the system is charted.
- Frontier seeded with system symbols instead of gate waypoints: every jump refused.
- An uncharted system hides its yard trait: chart before deciding there is no yard.
- Gate reads not persisted: every restart forgot the map. Claims and settled alternatives now
  live in the plan, not memory.
- A watcher cadence of ten minutes across 50 idle probes; a chart retry loop; re-reading the local
  gate on every route. All budget, no income.
- Killing the launcher's PID leaves the JVM running the old code; kill java.exe by command line
  and wait out the 90 s lease.

## Where the numbers are

Per-reset SQLite store `profile/agents/<AGENT>/data-<reset>.db`: `ledger` (charts, ships,
contracts), `transactions`, `request_log` (path, priority, status), `public_agent_samples`,
`gate_connections`, `phase_log`, `activity_log`. Commands: `summary`, `systems`, `leaders`,
`chains`, `chain`, `gate`, `idle`, `trades`. Prefer reading the store over CLI commands when the
run is live: each CLI boot spends the shared request budget.
