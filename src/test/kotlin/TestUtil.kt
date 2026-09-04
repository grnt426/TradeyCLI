import model.Location
import model.faction.FactionSymbol
import model.market.Market
import model.ship.*
import model.ship.components.*
import script.ScriptExecutor
import java.time.Instant

class TestUtil {
}

fun createShip(symbol: String = "0"): Ship {
    return Ship(
        "Symbol-$symbol",
        Navigation(
            "SystemSymbol",
            "WaypointSymbol",
            Route(
                createLocation(),
                createLocation(),
                createLocation(),
                Instant.now(),
                Instant.now()
            ),
            ShipNavStatus.IN_ORBIT,
            "FlightMode"
        ),
        Crew(0L, 0L, 0L, "", 0L, 0L),
        Fuel(0L, 0L, FuelConsumed(0L, "")),
        Cooldown("", 0L, 0L),
        Frame(
            "", "", "", 0L, 0L, 0L, null,
            Requirements()
        ),
        Engine("", "", "", null, 0L, Requirements()),
        Reactor("", "", "", null, 0L, Requirements()),
        emptyList(),
        emptyList(),
        Registration("", FactionSymbol.VOID.toString(), ShipRole.TRANSPORT),
        Cargo(100, 0, mutableListOf())
    )

}

fun createLocation(): Location {
    return Location(
        "",
        "",
        "",
        0L,
        0L
    )
}

fun createMarket(): Market {
    return Market(
        "market",
        mutableListOf(),
        mutableListOf(),
        mutableListOf()
    )
}

/**
 * Polls until [script] reports [expected] as its current state, or [timeoutMs] elapses.
 * Scripts run on their own timer thread, so a fixed sleep is racy; callers should still
 * assert on the state afterwards so a timeout produces a clear failure message.
 */
fun awaitState(script: ScriptExecutor<*>, expected: Any?, timeoutMs: Long = 2_000, pollMs: Long = 5) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (script.currentState != expected && System.currentTimeMillis() < deadline) {
        Thread.sleep(pollMs)
    }
}
