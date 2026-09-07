# The boom, system by system

The gate is open. From here the agent's world is a graph of systems, and each one the fleet
enters goes through its own small cycle. The plan keeps one record per system so the stage, the
budget and the ships are inspectable and editable by hand, the same way `plan.json` already holds
assignments and goals.

## Per-system stages

| stage | what happens | leaves when |
| --- | --- | --- |
| **Cascade** | A pioneer probe jumps in, reads the gate's connections (one request) and adds every gate it does not know to the frontier, marks unbuilt ones unreachable, loads the waypoints, and heads for the nearest shipyard. | the yard is found, or the system has none |
| **Rush** | At the yard the pioneer buys the system's rush kit: probes to chart (each chart paid ~28k last week) and one hauler to take the first arbitrage. The bought ships are born with jobs in *this* system: probes chart it then watch prices, the hauler trades it and gardens when routes run out. The pioneer moves on to the next frontier system. | every waypoint is charted and every market read |
| **Network** | If the system's own gate is unbuilt, this is a mini-escape: one hauler on `supplyGate` for that site, funded from the system's own earnings plus a share of the galaxy's bank, nursing its producers as at home. Systems with built gates skip this. | the gate completes, or the budget rule says no |
| **Settle** | The easy money is gone. Two haulers keep trading and gardening the markets that still pay, a probe keeps prices fresh, the rest are released to the frontier. | never; a settled system is a background income |

Cascade and Rush overlap: the pioneer reads the gate on arrival, so new frontier entries exist
before the first probe is bought. A system without a shipyard cannot rush on its own; its ships
are sent through the gate from the nearest system that has one (`trade --system`, `explore`, and
`chartSystem --system` already migrate).

## What the plan records

```
systems: {
  "X1-XX21": { stage: RUSH, gate: "X1-XX21-Z25C", gateBuilt: true, yard: "X1-XX21-B21B",
               pioneer: "TRIPLEHAT-B", arrivedAt: ..., budget: 350000, spent: 300000 }
}
frontier: ["X1-PA59-F17D", "X1-UQ20-I54"]      // gates we know of and have not entered
```

Fleet goals gain a `system`: a goal for two probes in X1-XX21 is met only by probes whose home
of record is X1-XX21, and the buyer buys only at a yard in that system. Ships bought in a system
belong to it until Settle releases them.

## Pioneers

The pioneer is a probe: it flies free, jumps for one antimatter, and its whole job is to get in,
read the gate, find the yard, buy the kit, and leave. Pioneer count is capped (two), because every
pioneer beyond the frontier's breadth is a probe not charting. The frontier is ordered by the
number of unknown connections a gate offers, then by distance, so the cascade widens fastest.

## Budget

Each Rush spends up to a per-system kit (two probes and one hauler, about 320k at home prices)
from the galaxy bank, never below a galaxy reserve. Network draws on the system's own tagged
income first (every transaction already carries the ship's behaviour; the system is the ship's
location) and then on a fixed share of the bank, so one far gate cannot starve the rest. Settle
spends nothing.

## What exists and what is new

Exists: `explore` (chart, read, jump on), `chartSystem --system`, `probeMarkets --maxAge`,
`trade --system` with gardening, `supplyGate` with nursing and the take-rate budget, gate
diversion on unbuilt gates, tagged transactions and the ledger, idle and activity time.

Built on 2026-09-06: the per-system record and frontier in `plan.json` (`systems`, `frontier`);
fleet goals with a `system` (`goal fleet TYPE N --system S`), counted and bought per system, and
seeded from any yard when the system has none; the `pioneer` behaviour (frontier gate, read the
far gate, buy the kit at the yard or chart on the spot); a bought ship's job in the system it was
bought for; stage transitions once a minute in `Strategy.advanceSystems` (charted and read ->
NETWORK when the gate is unbuilt, else SETTLE; NETWORK sends one hauler to the gate with
`--reserveShare 0.75`, the bank's 25% network share; SETTLE hands spare probes to the frontier
while pioneers are below the cap); the boom rebalance settles home, seeds the frontier from the
home gate and makes two probes pioneers; the `systems` command. Knobs: `Strategy.RUSH_KIT`
(350k), `GALAXY_RESERVE` (300k), `PIONEERS` (2), `NETWORK_SHARE` (0.25), `SETTLE_HAULERS`,
`SETTLE_PROBES`.

**Routes.** Every gate a ship reads is remembered (gate waypoint to its connections, seeded from the
store at start), and a ship bound for a system that is not a neighbour takes the shortest known
path hop by hop, growing the map as it passes. Home's yard therefore buys for the whole connected
network: a kit probe bought at A2 for a system two jumps out jumps twice. A pioneer only takes a
frontier gate whose entry system it can route to.

**Kits are counted in flight.** A system's goal counts the ships assigned to it as well as the ships
in it, so a kit bought at home is not bought again while it jumps out (33 probes went to ZN49 on
2026-09-07 before this). Yard prices climb with every purchase: a type above its ceiling
(`Strategy.priceCeiling`: probe 60k, hauler 450k) waits for the price to fall. Spare probes, a
chart target done or a fifth probe on one system, move to the known system with the most charts
left (`PROBES_PER_CHART` 4 per system), else pioneer, else watch prices where they stand.

**The request budget.** The API allows about 2.5 requests a second for the whole account, shared with
the bridge and any command-line query. On 2026-09-07 with 82 ships the run made 9,700 requests an
hour and 1,900 were refused: 5,800 were chart calls retrying waypoints another agent had charted
first, because our cached waypoint still said UNCHARTED. The order of worth when the budget is
short: charts and jumps (the boom's income), then trades, then market and yard reads. A refused
chart now clears the waypoint with one read; a gate already in the map is not re-read; boom
watchers re-read every thirty minutes; the `request_log` table says where the budget went.

**Growth.** The probe goal follows the frontier: a watcher plus one pioneer per open gate, up to
`MAX_PIONEERS` (8), and probes are bought unpaced (only ships at `PACED_PURCHASE_PRICE` or more
wait ten minutes between purchases). Every gate a pioneer finds is another probe bought at the
nearest yard, so the fleet grows with the map. Home's traders beyond `HOME_TRADERS` (2) spread over
the systems the pioneers have entered, `HAULERS_PER_SYSTEM` (2) each, one move a minute; a trader
sent to a system counts against its rush-kit hauler goal, so migration replaces a purchase.

Still to do: the cascade does not yet send a follow-up ship to a system whose only known gate is
two jumps away when the pioneer count is exhausted; per-system idle time.

## Measurements to keep

Per system: time from first jump to first chart, chart income, arbitrage income by hour since
arrival, health, idle share of its ships. Those four say whether the rush kit is the right size
and when Settle should begin.
