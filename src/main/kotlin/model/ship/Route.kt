package model.ship

import kotlinx.serialization.Serializable
import model.Location
import model.extension.InstantSerializer
import java.time.Instant

@Serializable
data class Route(
    val departure: Location,
    val origin: Location,
    val destination: Location,
    @Serializable(with = InstantSerializer::class) var arrival: Instant,
    @Serializable(with = InstantSerializer::class) val departureTime: Instant,
)
