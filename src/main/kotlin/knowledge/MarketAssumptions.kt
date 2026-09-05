package knowledge

import model.market.ActivityLevel
import model.market.ActivityLevel.GROWING
import model.market.ActivityLevel.RESTRICTED
import model.market.ActivityLevel.STRONG
import model.market.ActivityLevel.WEAK
import model.market.Market
import model.market.MarketTradeGood
import model.market.SupplyLevel
import model.market.SupplyLevel.ABUNDANT
import model.market.SupplyLevel.HIGH
import model.market.SupplyLevel.LIMITED
import model.market.SupplyLevel.MODERATE
import model.market.SupplyLevel.SCARCE
import model.market.TradeGoodType
import model.market.TradeGoodType.EXCHANGE
import model.market.TradeGoodType.EXPORT
import model.market.TradeGoodType.IMPORT
import model.market.TradeSymbol

/**
 * What we know about how markets move, as weights the decisions multiply into their scores. Every
 * number is a knob; docs/market-mechanics.md says where each came from and what changing it does.
 *
 * The short version, from the official markets page and confirmed live at X1-TH77 on 2026-09-04:
 * exports are produced over time and get cheaper as stock builds, but only while the producer's
 * imports are met, otherwise production is RESTRICTED and buying only raises the price; imports are
 * consumed over time and their sell price climbs back over hours; an import that reads HIGH or
 * ABUNDANT pays almost nothing and grows nothing. A weight of 0 means "never".
 */
data class MarketAssumptions(
    /** Buy exports first: they are produced where we buy them, so our buying grows production. */
    val sourceTypeWeight: Map<TradeGoodType, Double> = mapOf(EXPORT to 1.0, EXCHANGE to 0.8, IMPORT to 0.4),
    /** Sell to imports first: they are consumed where we sell, so our selling grows consumption. */
    val destinationTypeWeight: Map<TradeGoodType, Double> = mapOf(IMPORT to 1.0, EXCHANGE to 0.8, EXPORT to 0.4),
    /** A source with little stock is the good that market itself lacks; taking it starves the producer. */
    val sourceSupplyWeight: Map<SupplyLevel, Double> = mapOf(SCARCE to 0.0, LIMITED to 0.5, MODERATE to 0.9, HIGH to 1.0, ABUNDANT to 1.0),
    /** RESTRICTED production means the producer's imports are unmet: buying its export cannot grow output, so it is a poor source even when stocked. */
    val sourceActivityWeight: Map<ActivityLevel, Double> = mapOf(RESTRICTED to 0.3, WEAK to 0.8, GROWING to 1.0, STRONG to 1.0),
    /** From a RESTRICTED producer, take nothing once its stock is below this: it cannot replace what we take. */
    val restrictedTakeFloor: SupplyLevel = MODERATE,
    /** An import already well stocked pays little for more; ABUNDANT is where we buried A3's copper at 1 credit. */
    val destinationSupplyWeight: Map<SupplyLevel, Double> = mapOf(SCARCE to 1.0, LIMITED to 1.0, MODERATE to 0.9, HIGH to 0.5, ABUNDANT to 0.0),
    /**
     * When we want a producer to stay healthy (the gate's parts), how many trade volumes to take
     * per visit at each supply level; 0 means feed its inputs instead of buying.
     */
    val healthyBuyVolumes: Map<SupplyLevel, Double> = mapOf(SCARCE to 0.0, LIMITED to 0.0, MODERATE to 1.0, HIGH to 2.0, ABUNDANT to 4.0),
    /** A nursing leg may sell an input for less than it cost, down to this share of the buy price, because the goal is the producer's price, not the leg's. */
    val nurseMinSellRatio: Double = 0.5,
    /** Trade volumes of an input to deliver per nursing visit; the docs say consumption grows as we supply, so a few volumes is enough to move it. */
    val nurseVolumesPerVisit: Double = 2.0,
    /** Hours for a drained import's sell price to rebuild; observed at X1-TH77: 40-80% spreads were back after about six idle hours. */
    val importRecoveryHours: Double = 6.0,
)

/** The pure rules; the behaviours and the ranking ask these instead of reading levels themselves. */
object MarketHealth {
    /** How much we like buying [good] here; 0 means we never should. */
    fun sourceWeight(good: MarketTradeGood, a: MarketAssumptions = MarketAssumptions()): Double =
        (a.sourceTypeWeight[good.type] ?: 1.0) *
            (a.sourceSupplyWeight[good.supply] ?: 1.0) *
            (if (good.type == EXPORT) good.activity?.let { a.sourceActivityWeight[it] } ?: 1.0 else 1.0)

    /** How much we like selling [good] here; 0 means the market is saturated. */
    fun destinationWeight(good: MarketTradeGood, a: MarketAssumptions = MarketAssumptions()): Double =
        (a.destinationTypeWeight[good.type] ?: 1.0) * (a.destinationSupplyWeight[good.supply] ?: 1.0)

    fun saturated(good: MarketTradeGood, a: MarketAssumptions = MarketAssumptions()): Boolean = destinationWeight(good, a) <= 0.0

    /** True when taking from here would hurt: no weight at all, or a RESTRICTED producer below its take floor. */
    fun starved(good: MarketTradeGood, a: MarketAssumptions = MarketAssumptions()): Boolean =
        sourceWeight(good, a) <= 0.0 || (good.type == EXPORT && good.activity == RESTRICTED && good.supply < a.restrictedTakeFloor)

    /**
     * Units we may take from a producer per visit without pushing it unhealthy; 0 says nurse it
     * instead. A RESTRICTED producer counts as one stock level lower: it is not replacing what we take.
     */
    fun healthyUnits(good: MarketTradeGood, a: MarketAssumptions = MarketAssumptions()): Int {
        val level = if (good.type == EXPORT && good.activity == RESTRICTED) SupplyLevel.entries[(good.supply.ordinal - 1).coerceAtLeast(0)] else good.supply
        return ((a.healthyBuyVolumes[level] ?: 1.0) * good.tradeVolume).toInt()
    }

    /** The inputs of [good] that [producer] imports, most starved first. */
    fun starvedInputs(producer: Market, good: TradeSymbol): List<MarketTradeGood> =
        ImportMap.inputsOf(good, producer.imports.map { it.symbol })
            .mapNotNull { producer.good(it) }
            .sortedBy { it.supply.ordinal }

    fun describe(good: MarketTradeGood): String = "${good.supply}/${good.activity ?: "-"}"

    /** One line on why a producer is or is not worth buying from now. */
    fun explain(good: MarketTradeGood, a: MarketAssumptions = MarketAssumptions()): String {
        val units = healthyUnits(good, a)
        return when {
            units == 0 && good.type == EXPORT && good.activity == RESTRICTED -> "${describe(good)}: production is RESTRICTED, its imports are unmet, and stock is low; feed them"
            units == 0 -> "${describe(good)}: stock too low to take without pushing the price; feed its imports"
            else -> "${describe(good)}: healthy, up to $units units a visit"
        }
    }
}
