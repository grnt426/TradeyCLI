package sim

import api.ApiError
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import model.ApiJson
import model.actions.Survey
import model.market.TradeSymbol
import model.ship.FlightMode
import model.ship.ShipType

/**
 * The simulator behind HTTP: a Ktor [MockEngine] that speaks the server's wire format over a
 * [SimUniverse]. Lets the whole client, pacer and JSON decoding included, run against a system in
 * a box, and lets a test check that the real client and the simulator agree.
 */
class FakeServer(val universe: SimUniverse) {

    var requests: Int = 0
        private set

    /**
     * Ktor runs the handler on its I/O threads, so under a virtual clock nothing else may hold
     * pending delays while a request is in flight, or the clock jumps. Single-ship tests are
     * fine; multi-ship virtual-time runs use [SimApi] directly.
     */
    val engine: MockEngine = MockEngine { request -> handle(request) }

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

    private suspend fun MockRequestHandleScope.handle(request: HttpRequestData): HttpResponseData {
        requests++
        val path = request.url.encodedPath.removePrefix("/v2").trimEnd('/')
        val method = request.method.value
        val body: JsonObject? = runCatching { ApiJson.parseToJsonElement(String(request.body.toByteArray())).jsonObject }.getOrNull()
        return try {
            when {
                path == "" && method == "GET" -> raw(ApiJson.encodeToString(universe.status()))
                path == "/my/agent" -> data(universe.agent())
                path == "/my/ships" && method == "GET" -> paged(universe.listShips(), request)
                path == "/my/ships" && method == "POST" -> data(universe.purchaseShip(enumValueOf<ShipType>(body!!.str("shipType")), body.str("waypointSymbol")), HttpStatusCode.Created)
                path.startsWith("/my/ships/") -> ship(path.removePrefix("/my/ships/"), method, body)
                path.startsWith("/systems/") -> systems(path.removePrefix("/systems/"), request)
                else -> respond("""{"error":{"code":404,"message":"no route $method $path"}}""", HttpStatusCode.NotFound, jsonHeaders)
            }
        } catch (e: ApiError) {
            val error = buildJsonObject {
                put("code", e.code)
                put("message", e.apiMessage)
                e.data?.let { put("data", it) }
            }
            respond("""{"error":$error}""", HttpStatusCode.fromValue(e.status.takeIf { it in 400..599 } ?: 400), jsonHeaders)
        }
    }

    private fun MockRequestHandleScope.ship(rest: String, method: String, body: JsonObject?): HttpResponseData {
        val symbol = rest.substringBefore('/')
        val action = rest.substringAfter('/', "")
        return when {
            action == "" -> data(universe.ship(symbol))
            action == "orbit" -> data(universe.orbit(symbol))
            action == "dock" -> data(universe.dock(symbol))
            action == "navigate" -> data(universe.navigate(symbol, body!!.str("waypointSymbol")))
            action == "nav" && method == "PATCH" -> data(universe.setFlightMode(symbol, enumValueOf<FlightMode>(body!!.str("flightMode"))))
            action == "extract" -> data(universe.extract(symbol, null), HttpStatusCode.Created)
            action == "extract/survey" -> data(universe.extract(symbol, ApiJson.decodeFromJsonElement<Survey>(body!!)), HttpStatusCode.Created)
            action == "survey" -> data(universe.survey(symbol), HttpStatusCode.Created)
            action == "refuel" -> data(universe.refuel(symbol, body?.get("units")?.takeUnless { it is JsonNull }?.jsonPrimitive?.content?.toIntOrNull()))
            action == "sell" -> data(universe.sell(symbol, enumValueOf<TradeSymbol>(body!!.str("symbol")), body.str("units").toInt()), HttpStatusCode.Created)
            action == "jettison" -> raw("""{"data":{"cargo":${ApiJson.encodeToString(universe.jettison(symbol, enumValueOf<TradeSymbol>(body!!.str("symbol")), body.str("units").toInt()))}}}""")
            else -> respond("""{"error":{"code":404,"message":"no ship route $action"}}""", HttpStatusCode.NotFound, jsonHeaders)
        }
    }

    private fun MockRequestHandleScope.systems(rest: String, request: HttpRequestData): HttpResponseData {
        val parts = rest.split('/')
        return when {
            parts.size == 1 -> data(universe.system(parts[0]))
            parts.size == 2 && parts[1] == "waypoints" -> paged(universe.listWaypoints(parts[0]), request)
            parts.size == 3 && parts[1] == "waypoints" -> data(universe.listWaypoints(parts[0]).first { it.symbol == parts[2] })
            parts.size == 4 && parts[3] == "market" -> data(universe.market(parts[2]))
            parts.size == 4 && parts[3] == "shipyard" -> data(universe.shipyard(parts[2]))
            else -> respond("""{"error":{"code":404,"message":"no system route $rest"}}""", HttpStatusCode.NotFound, jsonHeaders)
        }
    }

    private inline fun <reified T> MockRequestHandleScope.data(value: T, status: HttpStatusCode = HttpStatusCode.OK): HttpResponseData =
        respond("""{"data":${ApiJson.encodeToString(value)}}""", status, jsonHeaders)

    private fun MockRequestHandleScope.raw(json: String): HttpResponseData = respond(json, HttpStatusCode.OK, jsonHeaders)

    private inline fun <reified T> MockRequestHandleScope.paged(items: List<T>, request: HttpRequestData): HttpResponseData {
        val page = request.url.parameters["page"]?.toIntOrNull() ?: 1
        val limit = request.url.parameters["limit"]?.toIntOrNull() ?: 10
        val slice = items.drop((page - 1) * limit).take(limit)
        return respond(
            """{"data":${ApiJson.encodeToString(slice)},"meta":{"total":${items.size},"page":$page,"limit":$limit}}""",
            HttpStatusCode.OK, jsonHeaders,
        )
    }

    private fun JsonObject.str(key: String): String = (this[key] as? JsonElement)?.jsonPrimitive?.content ?: error("missing $key")
}
