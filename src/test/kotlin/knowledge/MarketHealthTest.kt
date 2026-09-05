package knowledge

import model.market.ActivityLevel
import model.market.Market
import model.market.MarketTradeGood
import model.market.SupplyLevel
import model.market.TradeGood
import model.market.TradeGoodType
import model.market.TradeSymbol
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The market rules as pure functions of a listing, with the default weights from [MarketAssumptions]. */
class MarketHealthTest {
    private fun listing(type: TradeGoodType, supply: SupplyLevel, activity: ActivityLevel? = ActivityLevel.WEAK, symbol: TradeSymbol = TradeSymbol.IRON) =
        MarketTradeGood(symbol, type, 60, supply, 100, 50, activity)

    @Test
    fun `exports are the sources to buy and imports the destinations to sell`() {
        val export = listing(TradeGoodType.EXPORT, SupplyLevel.HIGH)
        val import = listing(TradeGoodType.IMPORT, SupplyLevel.LIMITED)
        assertTrue(MarketHealth.sourceWeight(export) > MarketHealth.sourceWeight(import))
        assertTrue(MarketHealth.destinationWeight(import) > MarketHealth.destinationWeight(export))
    }

    @Test
    fun `a starved producer is never a source and a saturated consumer never a destination`() {
        assertTrue(MarketHealth.starved(listing(TradeGoodType.EXPORT, SupplyLevel.SCARCE)))
        assertTrue(!MarketHealth.starved(listing(TradeGoodType.EXPORT, SupplyLevel.HIGH, ActivityLevel.RESTRICTED)), "a RESTRICTED producer with stock may still be drawn on")
        assertTrue(MarketHealth.starved(listing(TradeGoodType.EXPORT, SupplyLevel.LIMITED, ActivityLevel.RESTRICTED)), "but not once its stock is low: it is not replacing it")
        assertTrue(MarketHealth.sourceWeight(listing(TradeGoodType.EXPORT, SupplyLevel.HIGH, ActivityLevel.RESTRICTED)) < MarketHealth.sourceWeight(listing(TradeGoodType.EXPORT, SupplyLevel.HIGH)) / 2)
        assertTrue(!MarketHealth.starved(listing(TradeGoodType.EXPORT, SupplyLevel.MODERATE, ActivityLevel.GROWING)))
        assertTrue(MarketHealth.saturated(listing(TradeGoodType.IMPORT, SupplyLevel.ABUNDANT)), "where A3's copper went to 1 credit")
        assertTrue(!MarketHealth.saturated(listing(TradeGoodType.IMPORT, SupplyLevel.HIGH)))
    }

    @Test
    fun `the healthy take per visit scales with stock and stops when the producer is short`() {
        assertEquals(0, MarketHealth.healthyUnits(listing(TradeGoodType.EXPORT, SupplyLevel.LIMITED)))
        assertEquals(60, MarketHealth.healthyUnits(listing(TradeGoodType.EXPORT, SupplyLevel.MODERATE)))
        assertEquals(120, MarketHealth.healthyUnits(listing(TradeGoodType.EXPORT, SupplyLevel.HIGH)))
        assertEquals(120, MarketHealth.healthyUnits(listing(TradeGoodType.EXPORT, SupplyLevel.ABUNDANT, ActivityLevel.RESTRICTED)), "RESTRICTED counts one level lower")
        assertEquals(0, MarketHealth.healthyUnits(listing(TradeGoodType.EXPORT, SupplyLevel.MODERATE, ActivityLevel.RESTRICTED)))
        val stricter = MarketAssumptions(healthyBuyVolumes = mapOf(SupplyLevel.HIGH to 0.5))
        assertEquals(30, MarketHealth.healthyUnits(listing(TradeGoodType.EXPORT, SupplyLevel.HIGH), stricter))
    }

    @Test
    fun `a producer's starved inputs come from the import map, most starved first`() {
        val f47 = Market(
            symbol = "X1-TH77-F47",
            exports = listOf(TradeGood(TradeSymbol.FAB_MATS, "FAB_MATS", "")),
            imports = listOf(TradeGood(TradeSymbol.IRON, "IRON", ""), TradeGood(TradeSymbol.QUARTZ_SAND, "QUARTZ_SAND", ""), TradeGood(TradeSymbol.COPPER, "COPPER", "")),
            tradeGoods = listOf(
                listing(TradeGoodType.EXPORT, SupplyLevel.LIMITED, symbol = TradeSymbol.FAB_MATS),
                listing(TradeGoodType.IMPORT, SupplyLevel.MODERATE, symbol = TradeSymbol.IRON),
                listing(TradeGoodType.IMPORT, SupplyLevel.SCARCE, symbol = TradeSymbol.QUARTZ_SAND),
                listing(TradeGoodType.IMPORT, SupplyLevel.LIMITED, symbol = TradeSymbol.COPPER),
            ),
        )
        assertEquals(listOf(TradeSymbol.QUARTZ_SAND, TradeSymbol.IRON), MarketHealth.starvedInputs(f47, TradeSymbol.FAB_MATS).map { it.symbol }, "copper is not an input of FAB_MATS")
        assertEquals(listOf(TradeSymbol.ELECTRONICS, TradeSymbol.MICROPROCESSORS), ImportMap.inputs[TradeSymbol.ADVANCED_CIRCUITRY])
    }
}
