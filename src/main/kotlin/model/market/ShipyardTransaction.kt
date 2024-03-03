package model.market

import kotlinx.serialization.Serializable
import model.extension.InstantSerializer
import model.ship.ShipType
import java.time.Instant

@Serializable
data class ShipyardTransaction(
    val waypointSymbol: String,
    val shipSymbol: String,
    val shipType: ShipType,
    val price: Int,
    val agentSymbol: String,
    @Serializable(with = InstantSerializer::class) val timestamp: Instant,
)
