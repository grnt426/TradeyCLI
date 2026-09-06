package bridge

import api.Priority
import engine.Engine
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import model.ApiJson
import model.PublicAgent
import model.ServerStatus
import model.responsebody.JumpGate
import model.system.OrbitalNames
import model.system.System
import model.system.WaypointType
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

/**
 * What the galaxy screen knows beyond the engine's snapshot: the server's status with its
 * leaderboards, every agent on the server, every system, and which systems the jump gates
 * connect. None of it is needed to fly a ship, so all of it goes through [Priority.IDLE], and
 * what does not change within a reset (agents' homes, systems, gate connections) is kept in the
 * store, so a restart shows it at once instead of fetching it again.
 */
class Galaxy(private val engine: Engine) {
    @Volatile var status: ServerStatus? = null; private set
    private var statusAt = 0L
    private var cacheLoaded = false

    /** Every agent known, from the store's cache and the ranking pass. */
    val agents = ConcurrentHashMap<String, PublicAgent>()

    /** Every agent on the server as of the last ranking pass, richest first. */
    @Volatile var allAgents: List<PublicAgent> = emptyList(); private set

    /** Systems loaded by the galaxy screen itself, on top of the snapshot's. */
    val extraSystems = ConcurrentHashMap<String, System>()
    private val systemsRequested = ConcurrentHashMap.newKeySet<String>()

    /** System to the systems its jump gate connects to, for every gate read so far. */
    val connections = ConcurrentHashMap<String, Set<String>>()
    private val gatesRead = ConcurrentHashMap.newKeySet<String>()

    @Volatile var progress: String? = null; private set
    private var galaxyJob: Job? = null
    private var rankJob: Job? = null
    private var gateJob: Job? = null

    /** Our place among every agent, once the ranking pass has paged them; null until then. */
    @Volatile var rank: Rank? = null; private set
    data class Rank(val position: Int, val of: Int, val credits: Long)
    private var rankedAt = 0L

    fun systems(): Map<String, System> {
        val snap = engine.state.value.systems
        if (extraSystems.isEmpty()) return snap
        return HashMap(extraSystems).also { it.putAll(snap) }
    }

    /** What the store remembers from earlier runs this reset: agents and gate connections. */
    private suspend fun loadCache() {
        val store = engine.store ?: return
        runCatching {
            val cached = store.listPublicAgents()
            cached.forEach { agents[it.symbol] = it }
            if (allAgents.isEmpty()) allAgents = cached.sortedByDescending { it.credits }
            store.listGates().forEach { gate ->
                gatesRead += gate.symbol
                connections[OrbitalNames.getSectorSystem(gate.symbol)] = gate.connections.map { OrbitalNames.getSectorSystem(it) }.toSet()
            }
        }.onFailure { logger.warn(it) { "loading the galaxy cache failed" } }
    }

    /**
     * The server status, refreshed every five minutes; the snapshot's copy until the first refresh
     * lands. The first status starts everything else: the ranking pass (which also gives every
     * agent's home), then the galaxy crawl, then the gates outward from home.
     */
    fun status(): ServerStatus? {
        val nowNanos = java.lang.System.nanoTime()
        if (nowNanos - statusAt > 300_000_000_000L) {
            statusAt = nowNanos
            val client = engine.apiClient
            if (client != null) engine.scope.launch {
                if (!cacheLoaded && engine.store != null) {
                    cacheLoaded = true
                    loadCache()
                }
                runCatching { ApiJson.decodeFromJsonElement<ServerStatus>(client.getRoot("", Priority.IDLE)) }
                    .onSuccess {
                        status = it
                        requestHomeSystems(it)
                        rankUs()
                        loadGalaxy()
                        mapGates()
                    }
                    .onFailure { logger.warn(it) { "server status failed" } }
            }
        }
        return status ?: engine.state.value.serverStatus
    }

    /** The home system of every agent on the boards whose record we have, so the map can name it. */
    private fun requestHomeSystems(s: ServerStatus) {
        val symbols = (s.leaderboards?.mostCredits?.map { it.agentSymbol } ?: emptyList()) + (s.leaderboards?.mostSubmittedCharts?.map { it.agentSymbol } ?: emptyList())
        symbols.distinct().mapNotNull { agents[it] }.forEach { requestSystem(OrbitalNames.getSectorSystem(it.headquarters)) }
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
     * Pages every system into the store and the map, one idle request per twenty systems, after
     * the ranking pass has had its turn. Once per reset: the store keeps them.
     */
    fun loadGalaxy() {
        if (galaxyJob?.isActive == true || galaxyComplete) return
        val client = engine.apiClient ?: return
        val pages = galaxyRequests().coerceAtLeast(1)
        galaxyJob = engine.scope.launch {
            rankJob?.join()
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

    /**
     * Pages every agent to find where ours stands by credits, and remembers every record: an
     * agent's home does not change within a reset, so the store keeps them. Every half hour unless asked.
     */
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
                allAgents = sorted
                sorted.forEach { agents[it.symbol] = it }
                engine.store?.putPublicAgents(sorted)
                status?.let { requestHomeSystems(it) }
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

    /** Gates read so far. */
    val gatesMapped: Int get() = gatesRead.size

    /**
     * Reads jump gates outward from home, breadth first: each gate's connections name the next
     * systems whose gates to read, so the map grows as a network around us rather than at random.
     * One idle request per gate, at most [GATES_PER_RUN] a run; the store keeps every answer.
     */
    fun mapGates() {
        if (gateJob?.isActive == true) return
        val client = engine.apiClient ?: return
        val home = engine.state.value.hqSystem ?: return
        gateJob = engine.scope.launch {
            var fetched = 0
            val seen = HashSet<String>()
            var frontier = ArrayDeque(listOf(home))
            var rounds = 0
            while (frontier.isNotEmpty() && fetched < GATES_PER_RUN && rounds < 30) {
                val deferred = ArrayDeque<String>()
                while (frontier.isNotEmpty() && fetched < GATES_PER_RUN) {
                    val sys = frontier.removeFirst()
                    if (!seen.add(sys)) continue
                    val system = systems()[sys]
                    if (system == null) {
                        requestSystem(sys)
                        seen.remove(sys)
                        deferred += sys
                        continue
                    }
                    val gate = system.waypoints.firstOrNull { it.type == WaypointType.JUMP_GATE } ?: continue
                    if (gate.symbol !in gatesRead) {
                        fetched++
                        progress = "reading gates outward from home: ${gatesRead.size} read, $sys"
                        val read = runCatching { ApiJson.decodeFromJsonElement<JumpGate>(client.get("systems/$sys/waypoints/${gate.symbol}/jump-gate", Priority.IDLE)) }
                            .onFailure { logger.warn(it) { "gate ${gate.symbol} failed" } }.getOrNull() ?: continue
                        connections[sys] = read.connections.map { OrbitalNames.getSectorSystem(it) }.toSet()
                        gatesRead += gate.symbol
                        engine.store?.putGate(read)
                    }
                    connections[sys]?.forEach { if (it !in seen) frontier.addLast(it) }
                }
                if (deferred.isEmpty()) break
                // The systems we could not read yet are being fetched; give them a moment and come back.
                delay(10_000)
                frontier = deferred
                rounds++
            }
            if (fetched > 0) progress = "gates read: ${gatesRead.size}, ${connections.values.sumOf { it.size } / 2} connections"
        }
    }

    private companion object {
        const val GATES_PER_RUN = 120
    }
}
