package knowledge

import model.market.TradeGoodType
import model.market.TradeSymbol
import kotlin.math.roundToInt

/**
 * Price guesses for goods at markets nobody has visited yet. Only the ranking's "estimated"
 * column and the simulator's unseen markets use them; a visited market always wins. Numbers are
 * what an exchange market pays per unit, scaled to the first live readings of the 2026-08-30
 * reset (X1-TH77-H50 imports IRON_ORE at 60, COPPER_ORE 64, ALUMINUM_ORE 67; H52 exchanges
 * QUARTZ_SAND at 18, SILICON_CRYSTALS 33, ICE_WATER 13, AMMONIA_ICE 38; H53 imports DIAMONDS at
 * 105, PRECIOUS_STONES 87, GOLD 377, SILVER 297; FUEL 68/72 everywhere). Precious and rare ores
 * are still guesses, scaled down by the same ratio the common ores needed.
 */
object DefaultPrices {
    private val exchangeSell: Map<TradeSymbol, Int> = mapOf(
        TradeSymbol.QUARTZ_SAND to 18,
        TradeSymbol.SILICON_CRYSTALS to 33,
        TradeSymbol.ICE_WATER to 13,
        TradeSymbol.AMMONIA_ICE to 38,
        TradeSymbol.IRON_ORE to 38,
        TradeSymbol.COPPER_ORE to 40,
        TradeSymbol.ALUMINUM_ORE to 42,
        TradeSymbol.SILVER_ORE to 90,
        TradeSymbol.GOLD_ORE to 130,
        TradeSymbol.PLATINUM_ORE to 170,
        TradeSymbol.PRECIOUS_STONES to 55,
        TradeSymbol.DIAMONDS to 65,
        TradeSymbol.URANITE_ORE to 500,
        TradeSymbol.MERITIUM_ORE to 1200,
        TradeSymbol.HYDROCARBON to 40,
        TradeSymbol.LIQUID_HYDROGEN to 30,
        TradeSymbol.LIQUID_NITROGEN to 32,
        TradeSymbol.FUEL to 68,
        TradeSymbol.IRON to 43,
        TradeSymbol.COPPER to 83,
        TradeSymbol.ALUMINUM to 76,
        TradeSymbol.SILVER to 190,
        TradeSymbol.GOLD to 240,
        TradeSymbol.PLATINUM to 300,
        // Manufactured goods, from the export prices read live on 2026-09-04 (export sells at about 0.55 of base).
        TradeSymbol.FABRICS to 1280,
        TradeSymbol.FOOD to 1140,
        TradeSymbol.CLOTHING to 2590,
        TradeSymbol.MEDICINE to 2660,
        TradeSymbol.EQUIPMENT to 1690,
        TradeSymbol.ELECTRONICS to 1500,
        TradeSymbol.MACHINERY to 1600,
        TradeSymbol.MICROPROCESSORS to 1820,
        TradeSymbol.ADVANCED_CIRCUITRY to 3570,
        TradeSymbol.SHIP_PARTS to 2600,
        TradeSymbol.SHIP_PLATING to 2500,
        TradeSymbol.JEWELRY to 1860,
        TradeSymbol.AMMUNITION to 960,
        TradeSymbol.FIREARMS to 1990,
        TradeSymbol.ASSAULT_RIFLES to 2320,
        TradeSymbol.LAB_INSTRUMENTS to 3090,
        TradeSymbol.BIOCOMPOSITES to 3390,
        TradeSymbol.FAB_MATS to 1150,
        TradeSymbol.PLASTICS to 110,
        TradeSymbol.FERTILIZERS to 120,
        TradeSymbol.POLYNUCLEOTIDES to 150,
        TradeSymbol.EXPLOSIVES to 100,
        TradeSymbol.DRUGS to 3100,
        TradeSymbol.ROBOTIC_DRONES to 12600,
        TradeSymbol.LASER_RIFLES to 21000,
    )

    const val FALLBACK_SELL = 100

    /** What a market pays relative to an exchange: imports pay well, exports pay poorly. */
    fun typeFactor(type: TradeGoodType): Double = when (type) {
        TradeGoodType.IMPORT -> 1.6
        TradeGoodType.EXCHANGE -> 1.0
        TradeGoodType.EXPORT -> 0.55
    }

    /** What a market charges over what it pays: the spread, by market type (from the live readings). */
    fun purchaseFactor(type: TradeGoodType): Double = when (type) {
        TradeGoodType.IMPORT -> 2.0
        TradeGoodType.EXCHANGE -> 1.06
        TradeGoodType.EXPORT -> 2.0
    }

    /** What a market pays per unit. */
    fun sell(good: TradeSymbol, type: TradeGoodType): Int {
        val base = exchangeSell[good] ?: FALLBACK_SELL
        return (base * typeFactor(type)).roundToInt().coerceAtLeast(1)
    }

    /** What a market charges per unit; always above what it pays. */
    fun purchase(good: TradeSymbol, type: TradeGoodType): Int =
        (sell(good, type) * purchaseFactor(type)).roundToInt().coerceAtLeast(2)

    /** Units per transaction before the price moves. */
    fun volume(good: TradeSymbol): Int = when (good) {
        TradeSymbol.FUEL -> 180
        TradeSymbol.QUARTZ_SAND, TradeSymbol.ICE_WATER, TradeSymbol.IRON_ORE -> 40
        TradeSymbol.SILICON_CRYSTALS, TradeSymbol.AMMONIA_ICE, TradeSymbol.COPPER_ORE, TradeSymbol.ALUMINUM_ORE -> 20
        TradeSymbol.SILVER_ORE, TradeSymbol.GOLD_ORE, TradeSymbol.PLATINUM_ORE, TradeSymbol.PRECIOUS_STONES -> 10
        TradeSymbol.DIAMONDS, TradeSymbol.URANITE_ORE, TradeSymbol.MERITIUM_ORE -> 6
        else -> 20
    }

    fun knows(good: TradeSymbol): Boolean = good in exchangeSell
}
