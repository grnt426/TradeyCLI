package script.repo

import createMarket
import createShip
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertFalse

class PriceFetcherScriptTest {

    @Test
    fun `test still expired works`() {
        val script = PriceFetcherScript(createShip())
        val market = createMarket()
        market.lastRead.plusSeconds(60)
        assertFalse(script.stillExpired())
        script.market = market
        market.lastRead = Instant.now().minusSeconds(120)
        assert(script.stillExpired())
    }
}