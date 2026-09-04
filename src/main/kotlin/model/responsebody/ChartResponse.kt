package model.responsebody

import kotlinx.serialization.Serializable
import model.Agent
import model.system.Chart
import model.system.Waypoint

@Serializable
data class ChartTransaction(
    val waypointSymbol: String,
    val shipSymbol: String,
    val totalPrice: Long,
    val timestamp: String,
)

@Serializable
data class ChartResponse(
    val chart: Chart,
    val waypoint: Waypoint,
    val transaction: ChartTransaction,
    val agent: Agent,
)
