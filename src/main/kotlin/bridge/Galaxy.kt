package bridge

import api.Priority
import engine.Engine
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import model.ApiJson
import model.PublicAgent
import model.ServerStatus
import model.system.OrbitalNames
import model.system.System
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

/**
 * What the galaxy screen knows beyond the engine's snapshot: the server's status with its
 * leaderboards, the public records of the agents on them, every system we have seen, the whole
 * galaxy, and our rank among every agent. None of it is needed to fly a ship, so all of it goes
 * through [Priority.IDLE]: a request only starts when the pacer has a point nobody else wanted,
 * and the screen reads whatever has arrived so far.
 */
class Galaxy(private val engine: Engine) {
    @Volatile var status: ServerStatus? = null; private set
    private var statusAt = 0L

    val agents = ConcurrentHashMap<String, PublicAgent>()
    private val agentsRequested = ConcurrentHashMap.newKeySet<String>()

    /** Systems loaded by the galaxy screen itself, on top of the snapshot's. */
    val extraSystems = ConcurrentHashMap<String, System>()
    private val systemsRequested = ConcurrentHashMap.newKeySet<String>()

    @Volatile var progress: String? = null; private set
    private var galaxyJob: Job? = null
    private var rankJob: Job? = null

    /** Our place among every agent, once `R` has paged them; null until then. */
    @Volatile var rank: Rank? = null; private set
    data class Rank(val position: Int, val of: Int, val credits: Long)

    fun systems(): Map<String, System> {
        val snap = engine.state.value.systems
        if (extraSystems.isEmpty()) return snap
        return HashMap(extraSystems).also { it.putAll(snap) }
    }

    /**
     * The server status, refreshed every five minutes; the snapshot's copy until the first refresh
     * lands. The first status also starts the galaxy crawl and the ranking, both idle work.
     */
    fun status(): ServerStatus? {
        val nowNanos = java.lang.System.nanoTime()
        if (nowNanos - statusAt > 300_000_000_000L) {
            statusAt = nowNanos
            val client = engine.apiClient
            if (client != null) engine.scope.launch {
                runCatching { ApiJson.decodeFromJsonElement<ServerStatus>(client.getRoot("", Priority.IDLE)) }
                    .onSuccess {
                        status = it
                        requestLeaderboardAgents(it)
                        loadGalaxy()
                        rankUs()
                    }
                    .onFailure { logger.warn(it) { "server status failed" } }
            }
        }
        return status ?: engine.state.value.serverStatus
    }

    /** Fetches the public record of every agent on the leaderboards, and the system each calls home. */
    private fun requestLeaderboardAgents(s: ServerStatus) {
        val symbols = (s.leaderboards?.mostCredits?.map { it.agentSymbol } ?: emptyList()) + (s.leaderboards?.mostSubmittedCharts?.map { it.agentSymbol } ?: emptyList())
        val client = engine.apiClient ?: return
        val fresh = symbols.distinct().filter { agentsRequested.add(it) }
        if (fresh.isEmpty()) return
        engine.scope.launch {
            for (symbol in fresh) {
                val agent = runCatching { ApiJson.decodeFromJsonElement<PublicAgent>(client.get("agents/$symbol", Priority.IDLE)) }
                    .onFailure { logger.warn(it) { "public agent $symbol failed" } }.getOrNull() ?: continue
                agents[symbol] = agent
                requestSystem(OrbitalNames.getSectorSystem(agent.headquarters))
            }
        }
    }

    /** Loads one system's record if nobody has it yet, and keeps it in the store. */
    fun requestSystem(symbol: String) {
        if (systems().containsKey(symbol) || !systemsRequested.add(symbol)) return
        val client = engine.apiClient ?: return
        engine.scope.launch {
            runCatching { ApiJson.decodeFromJsonElement<System>(client.get("systems/$symbol", Priority.IDLE)) }
                .onSuccess { extraSystems[it.symbol] = it; engine.store?.putSystem(it) }
                .onFailure { logger.warn(it) { "system $symbol failed" } }
        }
    }

    /** How many requests the whole galaxy would take, from the server's count of systems. */
    fun galaxyRequests(): Int = ((status()?.stats?.systems ?: 0L) + 19).toInt() / 20

    /** True once every system the server counts is known. */
    val galaxyComplete: Boolean get() = (status?.stats?.systems ?: Long.MAX_VALUE) <= systems().size

    /**
     * Pages every system into the store and the map, one idle request per twenty systems. Runs
     * once per process unless the galaxy is still incomplete; `L` on the screen calls it too.
     */
    fun loadGalaxy() {
        if (galaxyJob?.isActive == true || galaxyComplete) return
        val client = engine.apiClient ?: return
        val pages = galaxyRequests().coerceAtLeast(1)
        galaxyJob = engine.scope.launch {
            try {
                for (page in 1..pages) {
                    progress = "loading the galaxy in idle moments: page $page of $pages"
                    val items = client.get("systems", Priority.IDLE, mapOf("page" to page.toString(), "limit" to "20")).jsonArray
                    if (items.isEmpty()) break
                    for (item in items) {
                        val system = ApiJson.decodeFromJsonElement<System>(item)
                        extraSystems[system.symbol] = system
                        engine.store?.putSystem(system)
                    }
                }
                progress = "galaxy loaded: ${systems().size} systems"
            } catch (e: Exception) {
                logger.warn(e) { "loading the galaxy failed" }
                progress = "loading the galaxy failed: ${e.message}"
            }
        }
    }

    /** How many requests ranking us would take, from the server's count of agents. */
    fun rankRequests(): Int = ((status()?.stats?.agents ?: 0L) + 19).toInt() / 20

    private var rankedAt = 0L

    /** Pages every agent to find where ours stands by credits; once every half hour unless asked. */
    fun rankUs(force: Boolean = false) {
        if (rankJob?.isActive == true) return
        val nowNanos = java.lang.System.nanoTime()
        if (!force && rank != null && nowNanos - rankedAt < 1_800_000_000_000L) return
        val client = engine.apiClient ?: return
        val ours = engine.state.value.agent?.symbol ?: return
        val pages = rankRequests().coerceAtLeast(1)
        rankJob = engine.scope.launch {
            try {
                val all = ArrayList<PublicAgent>()
                for (page in 1..pages) {
                    progress = "ranking in idle moments: page $page of $pages"
                    val items = client.get("agents", Priority.IDLE, mapOf("page" to page.toString(), "limit" to "20")).jsonArray
                    if (items.isEmpty()) break
                    items.forEach { all += ApiJson.decodeFromJsonElement<PublicAgent>(it) }
                }
                val sorted = all.sortedByDescending { it.credits }
                val position = sorted.indexOfFirst { it.symbol == ours }
                rank = if (position >= 0) Rank(position + 1, sorted.size, sorted[position].credits) else null
                rankedAt = java.lang.System.nanoTime()
                progress = if (position >= 0) "ranked #${position + 1} of ${sorted.size}" else "ranking: $ours not found among ${sorted.size} agents"
            } catch (e: Exception) {
                logger.warn(e) { "ranking failed" }
                progress = "ranking failed: ${e.message}"
            }
        }
    }
}
