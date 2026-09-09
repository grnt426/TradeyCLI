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

**The fleet mix.** Under the request budget the metric is credits per request. Probes chart at about
5,000 a request; a light hauler trades at about 1,000. A heavy freighter (225 hold, speed 36) does a
trade cycle for the same requests as a hauler with 2.8x the cargo at 2.4x the speed, so the boom buys
`FREIGHTERS` (3) and no more light haulers; the rush kit is two probes. `EXPLORERS` (2) carry warp
drives and work `warpChart`: the nearest unheld system without a jump gate within the tank, charted
and read, then the next. 3,930 of 7,026 systems have no gate and 27 lie within 800 of systems we
hold. Every goal is global and counts the type wherever it is, so nothing is bought twice.

**The request budget.** The API allows about 2.5 requests a second for the whole account, shared with
the bridge and any command-line query. On 2026-09-07 with 82 ships the run made 9,700 requests an
hour and 1,900 were refused: 5,800 were chart calls retrying waypoints another agent had charted
first, because our cached waypoint still said UNCHARTED. The order of worth when the budget is
short: charts and jumps (the boom's income), then trades, then market and yard reads. A refused
chart now clears the waypoint with one read; a gate already in the map is not re-read; boom
watchers re-read every thirty minutes; the `request_log` table says where the budget went.
The pacer spends the burst pool at its own average rate (`RateLimits.smoothBurst`, one point every
two seconds): on 2026-09-08 with 230 ships it released 4 to 15 requests in one second whenever the
pool refilled, those seconds carried nearly every 429, and a fifth of all requests were retries.

**Growth.** The probe goal follows the frontier: a watcher plus one pioneer per open gate, up to
`MAX_PIONEERS` (32), and probes are bought unpaced (only ships at `PACED_PURCHASE_PRICE` or more
wait five minutes between purchases). Every gate a pioneer finds is another probe bought at the
nearest yard, so the fleet grows with the map. Home's traders beyond `HOME_TRADERS` (2) spread over
the systems the pioneers have entered, `HAULERS_PER_SYSTEM` (2) each, one move a minute; a trader
sent to a system counts against its rush-kit hauler goal, so migration replaces a purchase.

**Traders rotate (2026-09-08).** A heavy freighter in a fresh system netted 3-7M in its first hour,
1.5-6M in its second and about 200k an hour from the third on, then sat there; 45% of all trader
time was "waiting" while 25 held systems had no trader. So every trader in the boom, once it has
been `TRADER_DWELL_MINUTES` (15) in a system, looks `RELOCATE_HOPS` (2) jumps out on each plan:
`Strategy.tradeValue` scores a system by the best route's rate plus a fading share of the next two,
as if the ship stood at its gate; a system that promises `RELOCATE_RATIO` (3) times the ship's best
local route and at least `RELOCATE_MIN_RATE` (50k/h), discounted 15% per extra jump, and with a slot
free (`TRADER_SLOTS_PER_SYSTEM` 2: one heavy at `HEAVY_HOLD` 150 or more, or two lights), gets the
ship. Greedy on purpose: drain where you stand, then move next door. A new freighter starts in the
system that promises the most (`bestTradingSystem`). `FREIGHTERS` is 25 (about 120 requests an hour
each; the budget stood at 1.46 of 2.5 a second with three) and one `BULK_FREIGHTERS` is bought to
measure a 490-hold, speed-60 hull against the heavies; both have a `priceCeiling`.

**Probes stop bouncing (2026-09-08).** A system's chart room is the smaller of `PROBES_PER_CHART` and
its uncharted waypoints (four probes were bound to each of four one-waypoint systems, arrived to a
claimed waypoint, finished, and were re-sent: 1,212 jumps in six hours), and a spare probe picks the
system with the most charts per jump from where it stands (`FAR_HOPS` 6 for an unknown distance)
rather than the most charts anywhere. Watchers in a system with no trader in it or bound to it
re-read every `BOOM_IDLE_WATCH_MINUTES` (180) instead of 30: prices only pay where a trader can act,
and 71 watchers were a quarter of the budget.

**The request floor (2026-09-08, 13:50 UTC).** With rotation and 32 pioneers the budget hit its
ceiling within an hour: 2.4 requests a second and 15% refused. Credits per hour is fooled by a
zero-distance cycle (a light hauler ran 27k loads between two markets in one orbit at ten requests
a load; the bulk freighter did the same on aluminum at 28k), so every plan now carries its request
count (`TradePlan.requests`: eight for the cycle plus one purchase and one sale per trade volume)
and a load below `MIN_CREDITS_PER_REQUEST` (5k) is not planned outside ESCAPE. A pioneered system
gets no kit while the fleet has `KIT_PROBES` (2) spare probes (42 were bought in seventy minutes),
and the buyer weighs a yard's price by `HOP_PRICE_PENALTY` (4%) per jump to reach it, and takes the
dearest unmet goal first (it left the cheapest heavy yard for a 21k kit probe after every purchase).
Kit goals are pruned by the boom tick whenever the fleet has spare probes; with every probe busy they
stay, and rightly: a chart is about 15k a request, better than any trade.

**Nine hours in (2026-09-09, 01:00 UTC).** Bank 479M, second place, 34M an hour gross over the run;
25 heavies at 511k an hour each, 21 lights at 263k, charts 15M an hour falling to 10M as 175
watchers sat one per system while 1,681 waypoints in 113 held systems went uncharted. So a lone
watcher in a system with no trader is spare while charts remain anywhere (never parked for it),
`FREIGHTERS` is 32, a trader with no local route at all looks `RELOCATE_HOPS_IDLE` (5) jumps out,
and a stranded explorer warps back to the nearest held gate in reach (`warpHome`).

**Explorers (2026-09-08).** A warp is taken only as far as the tank brings the ship back
(`warpReach`: half the tank, the whole tank where the far side is known to sell fuel), the ship
refuels at the nearest fuel market in the system rather than only where it stands, and when nothing
lies within reach it goes through the gates to the held system with the most gate-less neighbours
(`warpBase`) instead of waiting where it happens to be.

Still to do: the cascade does not yet send a follow-up ship to a system whose only known gate is
two jumps away when the pioneer count is exhausted; per-system idle time.

## Measurements to keep

Per system: time from first jump to first chart, chart income, arbitrage income by hour since
arrival, health, idle share of its ships. Those four say whether the rush kit is the right size
and when Settle should begin.
