package model.ship

import kotlinx.serialization.Serializable
import model.Location
import model.extension.InstantSerializer
import java.time.Instant

@Serializable
data class Route(
    /** Removed from the API in 2.2 in favour of [origin]; kept optional so older cached files still load. */
    val departure: Location? = null,
    val origin: Location,
    val destination: Location,
    @Serializable(with = InstantSerializer::class) var arrival: Instant,
    @Serializable(with = InstantSerializer::class) val departureTime: Instant,
)
