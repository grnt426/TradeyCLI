package model.ship

import model.Location
import kotlinx.serialization.Serializable

@Serializable
data class Route(
    val departure: Location,
    val origin: Location,
    val destination: Location,
    val arrival: String,
    val departureTime: String,
)
