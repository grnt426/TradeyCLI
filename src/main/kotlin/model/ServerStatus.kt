package model

import kotlinx.serialization.Serializable

/** `GET /`: the only unwrapped response in the API. Tells us which reset we are on, and how the galaxy is doing. */
@Serializable
data class ServerStatus(
    val status: String = "",
    val version: String = "",
    val resetDate: String,
    val description: String = "",
    val stats: ServerStats? = null,
    val health: ServerHealth? = null,
    val leaderboards: Leaderboards? = null,
    val serverResets: ServerResets? = null,
    val announcements: List<Announcement> = emptyList(),
)

@Serializable
data class ServerResets(val next: String = "", val frequency: String = "")

@Serializable
data class ServerStats(
    val accounts: Long? = null,
    val agents: Long = 0,
    val ships: Long = 0,
    val systems: Long = 0,
    val waypoints: Long = 0,
)

@Serializable
data class ServerHealth(val lastMarketUpdate: String? = null)

@Serializable
data class Leaderboards(
    val mostCredits: List<CreditsEntry> = emptyList(),
    val mostSubmittedCharts: List<ChartsEntry> = emptyList(),
)

@Serializable
data class CreditsEntry(val agentSymbol: String, val credits: Long)

@Serializable
data class ChartsEntry(val agentSymbol: String, val chartCount: Long)

@Serializable
data class Announcement(val title: String = "", val body: String = "")

/** `GET /agents/{symbol}`: what anyone may know about an agent. */
@Serializable
data class PublicAgent(
    val symbol: String,
    val headquarters: String,
    val credits: Long,
    val startingFaction: String,
    val shipCount: Long = 0,
)
