package model.requestbody

import kotlinx.serialization.Serializable
import model.ship.FlightMode

@Serializable
data class FlightModeRequest(val flightMode: FlightMode)
