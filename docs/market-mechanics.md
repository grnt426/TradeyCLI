# Market mechanics and the weights that encode them

What we know about how SpaceTraders markets move, where each piece came from, and which knob in
the code carries it. The knobs live in `knowledge/MarketAssumptions.kt` (health), the export
inputs in `knowledge/ImportMap.kt`, price impact in `behaviour/decisions/Trading.kt`
(`TradingAssumptions`), and the simulator's copy of all of it in `sim/SimRules.kt`. When a live
measurement contradicts a number here, change the number and this file together.

## What the official docs say

From https://docs.spacetraders.io/game-concepts/markets (the page body is rendered by script;
the text was pulled from the embedded page data on 2026-09-04):

- Listings are **exports** (produced at the waypoint, cheaper to buy), **imports** (consumed
  there, dearer to sell to) or **exchange** goods (neither; only agents move the price).
  "Buying at places that export goods, and selling at waypoints that import them will typically
  be the most profitable way to earn credits."
- **Exports:** "The purchase price of an export good tends to decrease over time as supply
  naturally increases from production. As agents buy up supply from an export good, production
  tends to increase to meet the demand. Be careful though, as this can only happen when there is
  an adequate supply of imports." When the imports are unmet, "production for export goods will
  be constrained, leading to a slower decrease in export prices."
- **Imports:** "The sell price of an import good tends to increase over time as supply naturally
  decreases from consumption. As agents supply more of an import good, consumption will typically
  increase as the price for that good goes down."
- "Maintaining a healthy market is a key part of maximizing profitability for agents. Markets that
  are well supplied tend to offer much more consistent margins on goods, but they require more
  attention to maintain."

From the OpenAPI schema: **activity** (WEAK, GROWING, STRONG, RESTRICTED) is "how strong
production is" for an export and "how strong consumption is" for an import; **supply** (SCARCE,
LIMITED, MODERATE, HIGH, ABUNDANT) is the stock; **tradeVolume** is the most units one trade may
move and "a market with a low trade volume will have large price swings".

The community wiki's `Market-Export-&-Import-Map.md` lists what every export is made from;
`ImportMap.kt` is generated from it.

## What we measured at X1-TH77 (2026-09-04)

| Observation | Number | Where it lives |
| --- | --- | --- |
| Selling into an import drops its price per trade volume, harder each time | 2.1% then x1.3 per volume | `TradingAssumptions.sellImpactPerVolume`, `sellImpactGrowth` |
| Buying from an export raises its price per trade volume | 0.56% then x1.22 per volume | `TradingAssumptions.buyImpactPerVolume`, `buyImpactGrowth` |
| 200 FAB_MATS bought at F47 moved it HIGH -> LIMITED and 1,256 -> 1,975; it did not fall back in the hours after | two supply levels per 1.57x | `SimRules.supplyLevelPerPriceRatio` |
| Six idle hours rebuilt import spreads of 40-80% (SHIP_PLATING 4,428 -> 7,964; ELECTRONICS 1,922 -> 2,819) | ~6 h | `MarketAssumptions.importRecoveryHours` |
| Over-feeding A3 drove its COPPER import to ABUNDANT, paying 1 credit a unit | ABUNDANT = 0 weight | `MarketAssumptions.destinationSupplyWeight` |
| Buying COPPER out of H50 while nobody supplied its ore left every H50 export RESTRICTED/SCARCE at 5x the price | RESTRICTED = 0 weight | `MarketAssumptions.sourceActivityWeight` |

## The rules, as knobs

`MarketAssumptions` (defaults in brackets):

| Knob | Rule it carries |
| --- | --- |
| `sourceTypeWeight` [export 1.0, exchange 0.8, import 0.4] | buy exports first |
| `destinationTypeWeight` [import 1.0, exchange 0.8, export 0.4] | sell to imports first |
| `sourceSupplyWeight` [SCARCE 0, LIMITED 0.5, MODERATE 0.9, HIGH 1, ABUNDANT 1] | do not take a producer's last stock |
| `sourceActivityWeight` [RESTRICTED 0, WEAK 0.8, GROWING 1, STRONG 1] | a RESTRICTED producer cannot grow output; feed it instead |
| `destinationSupplyWeight` [SCARCE 1, LIMITED 1, MODERATE 0.9, HIGH 0.5, ABUNDANT 0] | a stocked consumer pays little and grows nothing |
| `healthyBuyVolumes` [SCARCE 0, LIMITED 0, MODERATE 1, HIGH 2, ABUNDANT 4] | trade volumes the gate hauler may take per visit without pushing the producer unhealthy |
| `takeVolumesPerHour` [SCARCE 0, LIMITED 1, MODERATE 2.5, HIGH 4, ABUNDANT 6] | trade volumes an hour a producer can be drawn on without its stock falling; shared by all our ships via `TakeBudget` (measured at F47: ~100/h at MODERATE held, ~160/h drained it) |
| `restrictedTakeFactor` [0.5], `takeBucketHours` [1.5] | a RESTRICTED producer's rate is halved; an idle producer banks at most that many hours of rate |
| `importFeedBonus` [SCARCE 2, LIMITED 1.5] | how much more a sale to a starved importer is worth to a health-first decision (mining in ESCAPE) |
| `nurseMinSellRatio` [0.5] | a nursing leg may lose up to half its cost; the goal is the producer's price |
| `nurseVolumesPerVisit` [2] | how much input to deliver per nursing visit |
| `importRecoveryHours` [6] | how long a drained import takes to pay again |

Who uses them:

- `Trading.rank` multiplies a route's credits per hour by the source and destination weights into
  `TradePlan.score` and sorts by it; a weight of 0 drops the route. `trades` shows both columns.
- `feed` (chain bees) skips a leg whose consumer is saturated or whose producer is starved.
- `supplyGate` takes only `healthyUnits` per visit from a part's producer and, when that is zero,
  nurses the producer: it hauls the most starved input from the cheapest healthy source and sells it
  there, with the budget above the reserve. `gate` prints each producer's health and its inputs.
  `--nurse off` restores blind buying.

## The simulator's copy

`SimRules` carries the same knowledge as guesses to be calibrated: supply moves one level per
`supplyLevelPerPriceRatio` of price away from the seed; an export whose inputs are LIMITED or worse
reads RESTRICTED and recovers at `restrictedRecoveryFactor`; one whose inputs are all HIGH recovers
at `fedRecoveryFactor` and settles at `fedExportTarget` of its seed price; a listing reads GROWING
after `growingAfterVolumes` of recent trade.

## Benchmarks (leaderboard, 2026-09-04, reset of 2026-08-30)

Leader 2.55 billion credits after 5.5 days with 33,041 charts submitted; eight agents above
850 million; 172 active agents flying 46,519 ships. The community's early benchmark is about
3 million in the first 8 hours; our United agent did 175k -> 2.0 million in six hours in an empty
system. Fleet size, not per-ship rate, is our gap.
