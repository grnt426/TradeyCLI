package storage

import kotlinx.coroutines.runBlocking
import model.WaypointTrait
import model.WaypointTraitSymbol
import model.market.ActivityLevel
import model.market.Market
import model.market.MarketTradeGood
import model.market.SupplyLevel
import model.market.TradeGood
import model.market.TradeGoodType
import model.market.TradeSymbol
import model.system.Waypoint
import model.system.WaypointType
import createShip
import java.io.File
import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentStoreTest {

    private fun tempDir(): File = Files.createTempDirectory("tradey-store").toFile()

    private fun waypoint(symbol: String, system: String, vararg traits: WaypointTraitSymbol) = Waypoint(
        systemSymbol = system, symbol = symbol, type = WaypointType.PLANET, x = 1, y = 2,
        traits = traits.map { WaypointTrait(it, it.name, "") },
    )

    private fun market(symbol: String, price: Int) = Market(
        symbol = symbol,
        exports = mutableListOf(TradeGood(TradeSymbol.IRON_ORE, "Iron ore", "")),
        imports = mutableListOf(),
        exchange = mutableListOf(),
        tradeGoods = mutableListOf(
            MarketTradeGood(TradeSymbol.IRON_ORE, TradeGoodType.EXPORT, 10, SupplyLevel.MODERATE, price, price - 5, ActivityLevel.STRONG)
        ),
    )

    @Test
    fun `entities round trip and filter by system`() = runBlocking {
        val store = AgentStore.open(tempDir(), "TEST", "2026-08-30")
        store.putWaypoints(listOf(waypoint("X1-AA-A1", "X1-AA", WaypointTraitSymbol.MARKETPLACE), waypoint("X1-BB-B1", "X1-BB")))
        assertEquals(listOf("X1-AA-A1"), store.listWaypoints("X1-AA").map { it.symbol })
        assertEquals(2, store.listWaypoints().size)
        assertEquals(WaypointTraitSymbol.MARKETPLACE, store.listWaypoints("X1-AA").single().traits.single().symbol)

        store.putShips(listOf(createShip("1"), createShip("2")))
        assertEquals(listOf("Symbol-1", "Symbol-2"), store.listShips().map { it.symbol }.sorted())
        store.putShips(listOf(createShip("3")))
        assertEquals(listOf("Symbol-3"), store.listShips().map { it.symbol }, "putShips replaces the fleet")
        store.close()
    }

    @Test
    fun `market writes append to the price history`() = runBlocking {
        val store = AgentStore.open(tempDir(), "TEST", "2026-08-30")
        store.putMarket(market("X1-AA-A1", 100), Instant.ofEpochMilli(1_000))
        store.putMarket(market("X1-AA-A1", 110), Instant.ofEpochMilli(2_000))
        assertEquals(1, store.listMarkets("X1-AA").size, "one market row, replaced")
        assertEquals(110, store.listMarkets("X1-AA").single().tradeGoods.single().purchasePrice)
        val history = store.priceHistory("X1-AA-A1", TradeSymbol.IRON_ORE)
        assertEquals(listOf(100, 110), history.map { it.good.purchasePrice })
        assertEquals(Instant.ofEpochMilli(2_000), history.last().observedAt)
        store.close()
    }

    @Test
    fun `databases from earlier resets are archived on open`() = runBlocking {
        val dir = tempDir()
        AgentStore.open(dir, "TEST", "2026-08-23").close()
        assertTrue(File(dir, "data-2026-08-23.db").exists())

        val store = AgentStore.open(dir, "TEST", "2026-08-30")
        assertFalse(File(dir, "data-2026-08-23.db").exists(), "old reset moved out of the way")
        assertTrue(File(dir, "archive/data-2026-08-23.db").exists())
        assertTrue(File(dir, "data-2026-08-30.db").exists())
        assertEquals("2026-08-30", store.resetDate)
        store.close()
    }
}
