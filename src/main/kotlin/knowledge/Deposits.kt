package knowledge

import model.WaypointTraitSymbol
import model.market.TradeSymbol

/**
 * What an asteroid's traits say about what a mining laser pulls out of it. The trait descriptions
 * in api-docs/wiki/Waypoints.md name the goods; the weights are working assumptions until the
 * extraction log (`extractions` table) says otherwise. Both the ranking and the simulator use
 * this table, so a correction here moves both.
 */
object Deposits {
    val byTrait: Map<WaypointTraitSymbol, Map<TradeSymbol, Double>> = mapOf(
        WaypointTraitSymbol.MINERAL_DEPOSITS to mapOf(TradeSymbol.QUARTZ_SAND to 50.0, TradeSymbol.SILICON_CRYSTALS to 50.0),
        WaypointTraitSymbol.COMMON_METAL_DEPOSITS to mapOf(TradeSymbol.IRON_ORE to 45.0, TradeSymbol.COPPER_ORE to 30.0, TradeSymbol.ALUMINUM_ORE to 25.0),
        WaypointTraitSymbol.PRECIOUS_METAL_DEPOSITS to mapOf(TradeSymbol.SILVER_ORE to 45.0, TradeSymbol.GOLD_ORE to 35.0, TradeSymbol.PLATINUM_ORE to 20.0),
        WaypointTraitSymbol.RARE_METAL_DEPOSITS to mapOf(TradeSymbol.URANITE_ORE to 70.0, TradeSymbol.MERITIUM_ORE to 30.0),
        WaypointTraitSymbol.ICE_CRYSTALS to mapOf(TradeSymbol.ICE_WATER to 50.0, TradeSymbol.AMMONIA_ICE to 50.0),
        WaypointTraitSymbol.FROZEN to mapOf(TradeSymbol.ICE_WATER to 60.0, TradeSymbol.AMMONIA_ICE to 40.0),
    )

    /** What a gas siphon pulls out of a gas giant. Even weights; a guess until the extraction log says otherwise. */
    val gasGiant: Map<TradeSymbol, Double> = mapOf(TradeSymbol.HYDROCARBON to 0.34, TradeSymbol.LIQUID_HYDROGEN to 0.33, TradeSymbol.LIQUID_NITROGEN to 0.33)

    /** Traits that hint the rock is fragile and may destabilize sooner under repeated extraction. */
    val fragileTraits: Set<WaypointTraitSymbol> = setOf(WaypointTraitSymbol.UNSTABLE_COMPOSITION, WaypointTraitSymbol.HOLLOWED_INTERIOR)

    /** Traits that wear a ship faster while it works there. */
    val hazardTraits: Set<WaypointTraitSymbol> = setOf(
        WaypointTraitSymbol.RADIOACTIVE, WaypointTraitSymbol.EXPLOSIVE_GASES, WaypointTraitSymbol.MICRO_GRAVITY_ANOMALIES,
        WaypointTraitSymbol.DEBRIS_CLUSTER, WaypointTraitSymbol.DEEP_CRATERS,
    )

    /** Probability of each good per extraction, over every deposit trait present. Empty when nothing is minable. */
    fun yieldMix(traits: Set<WaypointTraitSymbol>): Map<TradeSymbol, Double> {
        val pools = traits.mapNotNull { byTrait[it] }
        if (pools.isEmpty()) return emptyMap()
        val merged = mutableMapOf<TradeSymbol, Double>()
        pools.forEach { pool ->
            val total = pool.values.sum()
            pool.forEach { (good, weight) -> merged[good] = (merged[good] ?: 0.0) + weight / total / pools.size }
        }
        return merged
    }
}
