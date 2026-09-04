package engine

import api.ApiClient
import api.RequestPacer
import api.SpaceTradersApi
import createShip
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import model.ApiJson
import model.WaypointTrait
import model.WaypointTraitSymbol
import model.market.Market
import model.system.System
import model.system.Waypoint
import model.system.WaypointType
import storage.AgentStore
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Boots the engine against a scripted API and a temporary store. */
class EngineTest {

    private val json = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
    private val calls = ConcurrentHashMap<String, Int>()

    private val waypoints = listOf(
        Waypoint("X1-AA", "X1-AA-A1", WaypointType.PLANET, 0, 0, traits = listOf(WaypointTrait(WaypointTraitSymbol.MARKETPLACE, "Marketplace", ""))),
        Waypoint("X1-AA", "X1-AA-B2", WaypointType.MOON, 5, 5),
    )
    private val system = System("X1", "X1-AA", "RED_STAR", 0, 0, factions = emptyList())
    private val market = Market("X1-AA-A1", mutableListOf(), mutableListOf(), mutableListOf())

    private fun handler() = MockEngine { request ->
        val path = request.url.encodedPath
        calls.merge(path, 1, Int::plus)
        fun data(json: String) = respond("""{"data":$json}""", HttpStatusCode.OK, this@EngineTest.json)
        when (path) {
            "/v2/" -> respond("""{"status":"ok","version":"v2.3.0","resetDate":"2026-08-30","serverResets":{"next":"2026-09-06T13:00:00.000Z","frequency":"weekly"}}""", HttpStatusCode.OK, this@EngineTest.json)
            "/v2/my/agent" -> data("""{"symbol":"TEST","headquarters":"X1-AA-A1","credits":175000,"startingFaction":"COSMIC","shipCount":1}""")
            "/v2/my/ships" -> respond("""{"data":[${ApiJson.encodeToString(createShip("1"))}],"meta":{"total":1,"page":1,"limit":20}}""", HttpStatusCode.OK, this@EngineTest.json)
            "/v2/systems/X1-AA" -> data(ApiJson.encodeToString(system))
            "/v2/systems/X1-AA/waypoints" -> respond("""{"data":${ApiJson.encodeToString(waypoints)},"meta":{"total":2,"page":1,"limit":20}}""", HttpStatusCode.OK, this@EngineTest.json)
            "/v2/systems/X1-AA/waypoints/X1-AA-A1/market" -> data(ApiJson.encodeToString(market))
            else -> error("unexpected request $path")
        }
    }

    private fun engine(storeDir: File): Engine {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val pacer = RequestPacer(scope)
        return Engine(
            scope = scope,
            pacer = pacer,
            apiFactory = { token -> SpaceTradersApi(ApiClient(token, pacer, handler())) },
            storeFactory = { symbol, reset -> AgentStore.open(storeDir, symbol, reset) },
        )
    }

    @Test
    fun `boot loads agent, fleet and home system, then caches for the next boot`() = runBlocking {
        val storeDir = Files.createTempDirectory("tradey-engine").toFile()
        val steps = mutableListOf<String>()
        var known: String? = null

        val first = engine(storeDir)
        first.boot("token", progress = { steps += it }, onAgentKnown = { known = it.symbol })
        first.awaitSystem("X1-AA")
        val snap = first.snapshot
        assertEquals("TEST", snap.agent?.symbol)
        assertEquals("TEST", known)
        assertEquals("2026-08-30", snap.resetDate)
        assertEquals("X1-AA", snap.hqSystem)
        assertEquals(listOf("Symbol-1"), snap.ships.keys.toList())
        assertEquals(2, snap.waypointsIn("X1-AA").size)
        assertEquals(1, snap.marketsIn("X1-AA").size)
        assertTrue(steps.first().startsWith("Connecting"), steps.toString())
        assertTrue(steps.any { it.startsWith("Loading home system X1-AA") }, steps.toString())
        first.shutdown()

        val second = engine(storeDir)
        second.boot("token")
        second.awaitSystem("X1-AA")
        assertEquals(2, second.snapshot.waypointsIn("X1-AA").size, "waypoints came from the store")
        assertEquals(1, calls["/v2/systems/X1-AA/waypoints"], "waypoints fetched once across both boots")
        assertEquals(1, calls["/v2/systems/X1-AA/waypoints/X1-AA-A1/market"], "market fetched once across both boots")
        assertEquals(2, calls["/v2/my/ships"], "the fleet is refreshed on every boot")
        second.shutdown()
    }
}
