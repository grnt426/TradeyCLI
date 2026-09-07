# The three phases of a reset

The plan carries a `phase` (`plan.json`, `phase` command). `knowledge/Strategy.kt` turns it into
the market weights, the margin floor, the default job for a bought ship, what a ship does after its
behaviour finishes, and the fleet goals a fresh agent starts with. Nothing else reads the phase, so
changing a phase's behaviour means editing one object.

| | ESCAPE | BOOM | LATE |
| --- | --- | --- | --- |
| Situation | home gate unfinished | gate open, map unread | stable, gates buildable |
| Goal | keep producers healthy, fund the gate | discover markets, chart, cash boom | profit first, health for margins |
| Health weights | strict (`MarketAssumptions()` defaults) | HIGH importers pay 0.7 | RESTRICTED sources 0.5, ABUNDANT importers 0.3, take floor LIMITED |
| Margin floor | 15% | 10% | 10% |
| Mining sells | where the buyer is starved: SCARCE importer counts x2, LIMITED x1.5 | by price with a small feed bonus | by price, skipping saturated buyers |
| Bought hauler | by arrival: `supplyGate` on the home site, `runContract`, `trade`, then `FEEDERS` (2) on the gate chains, then trade | trade | trade |
| Bought probe | `probeMarkets`, then `expand` at a yard | `explore`, then `probeMarkets` where it stops | `probeMarkets` |
| Fresh agent's goals | 5 + `FEEDERS` haulers (200k), 2 surveyors (100k), 2 mining drones (150k, only where a rock is within reach of a buyer) | 2 haulers (300k), 2 probes (100k) | none |
| Leaves when | the gate completes (`supplyGate` advances the plan to BOOM itself) | by hand: `phase late` | - |

**The feeders.** The clock on the escape is the producers' output, not the bank: on 2026-09-06 F55
made about 40 FAB_MATS an hour with its iron flickering LIMITED while 1.5M sat unspendable and the
trade routes were down to two. The supervisor seeds one chain per unpaid material (`gate-FAB_MATS`,
`gate-ADVANCED_CIRCUITRY`): every input the producer imports, from the cheapest other market, and
the same for an input's own producer in the system. `FEEDERS` haulers work them with `feed`,
whether or not a leg pays (iron costs 8 a unit more at the refinery than the fab pays); a source
down to LIMITED gives up one trade volume a visit. `chain` prints each chain's ledger and verdict.

**A surplus promotes a hauler.** Once feeding works the producer outruns one hauler: F55 read
MODERATE/STRONG with its trade volume doubled to 43 on the evening of 2026-09-06 while D47 sat
ABUNDANT in circuitry. When a gate material's producer reads `gateSurplusAt` (HIGH) or better, the
escape tick promotes the first trading hauler by symbol to `supplyGate`, one per minute, up to
`RUSH_HAULERS`. Nobody is demoted: a gate hauler trades once when it finds nothing to take. A
producer whose export is GROWING or STRONG is taken from rather than fed first (`takeWhenGrowing`),
and nursing, feeding and gardening stop at an import that reads `feedUntil` (HIGH).

**The final push.** With `FINAL_PUSH_UNITS` (100) or fewer left on the whole bill, the gate haulers
buy regardless of the producers' health: no take budget, no nursing, no feeding first. A drained
producer recovers in hours, and by the time another gate needs its parts the boom has the whole
galaxy to buy them in.

**Boom readiness.** With `READY_HOURS` (1) of hauling left at the last two hours' delivery rate,
the escape tick raises the probe goal to a watcher plus `PIONEERS` and sends every probe but the
buyer to wait at the gate, reading its market. When the last unit lands and the phase turns, the
boom's rebalance makes them pioneers where they already stand, and the first jumps happen minutes
after the gate opens instead of half an hour later.

**The gate rush.** ESCAPE is rushed, but not at the cost of market health or of the capital the
boom needs. The gate hauler re-reads the bill every load and prices what is left at the cheapest
listing in the system. Once the bank covers `RUSH_COMFORT` (1.5) times that, plus the
`POST_GATE_RESERVE` (500k) the boom starts with, it raises the plan's hauler goal to
`RUSH_HAULERS` (3) with that reserve; the probe at the yard buys them and each goes to the gate
in nursing mode. When the site completes, every hauler finishes and becomes a boom trader. The
constants live in `knowledge/Strategy.kt`; the summary screen says when the rush is on.

The rush is rate-limited, not bang-bang. Three haulers each taking a full load from one producer
drained it from MODERATE to LIMITED in an hour on 2026-09-05 and then all three sat idle. Now every
producer has a take-rate bucket (`knowledge/TakeBudget.kt`) shared by all our ships, refilled at
`takeVolumesPerHour` for the stock level last read (SCARCE 0, LIMITED 1, MODERATE 2.5, HIGH 4,
ABUNDANT 6 trade volumes an hour, halved when RESTRICTED) and banked for at most an hour and a
half. A hauler takes what the bucket holds, so the draw follows the stock up and down instead of
stopping and restarting at a threshold. Haulers also pick the material whose producer can spare
the most right now, and when nothing can be taken they nurse two levels deep: an input nobody can
spare (iron) sends the hauler to feed that input's own producer (the refinery) with what it lacks
(ore).

In ESCAPE the point is never to tip a market into SCARCE or RESTRICTED. In LATE the point is the
opposite: loosen the weights on purpose and record where each market tips, so the limits become
numbers. Every market reading already lands in `market_prices` with supply and activity, so the
measurement is the ordinary run; the analysis is a query over that table.

## The experiment (from 2026-09-04)

TRIPLEHAT (VOID, X1-TH77) got the phased plan late, after a day of blended strategy.
TRIPLEHATCE2 (COSMIC, X1-JT70, gate unfinished, nobody contributing) runs it from its first
minute: frigate trading on the ESCAPE weights, probe reading markets then buying the ESCAPE fleet,
each new hauler going to the gate. `race` prints both agents' bank over time, fleet, gate deliveries
and phase side by side; the question is how fast the fresh agent catches the old one.
