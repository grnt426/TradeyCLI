package model.ship

import kotlinx.serialization.Serializable

@Serializable
data class Navigation(
    val systemSymbol: String,
    val waypointSymbol: String,
    val route: Route,
    val status: String,
    val flightMode: String
)
