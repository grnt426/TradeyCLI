import model.Location
import model.faction.FactionSymbol
import model.market.Market
import model.ship.*
import model.ship.components.*

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
                "",
                ""
            ),
            ShipNavStatus.IN_ORBIT,
            "FlightMode"
        ),
        Crew(0L, 0L, 0L, "", 0L, 0L),
        Fuel(0L, 0L, FuelConsumed(0L, "")),
        Cooldown("", 0L, 0L),
        Frame(
            "", "", "", 0L, 0L, 0L, 0L,
            Requirements()
        ),
        Engine("", "", "", 0L, 0L, Requirements()),
        Reactor("", "", "", 0L, 0L, Requirements()),
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