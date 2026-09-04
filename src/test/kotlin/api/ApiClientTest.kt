package api

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

@OptIn(ExperimentalCoroutinesApi::class)
class ApiClientTest {

    private val json = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

    private fun client(scope: CoroutineScope, timeSource: TimeSource, handler: MockRequestHandler): ApiClient =
        ApiClient(
            token = "agent-token",
            pacer = RequestPacer(scope, timeSource = timeSource),
            engine = MockEngine(handler),
            retry = RetryPolicy(maxAttempts = 3, baseDelay = 100.milliseconds),
        )

    @Test
    fun `unwraps the data envelope and sends the bearer token`() = runTest {
        var authHeader: String? = null
        val api = client(backgroundScope, testScheduler.timeSource) { request ->
            authHeader = request.headers[HttpHeaders.Authorization]
            respond("""{"data":{"symbol":"TRADEY"}}""", HttpStatusCode.OK, json)
        }
        val data = api.get("my/agent", Priority.INTERACTIVE)
        assertEquals("TRADEY", data.jsonObject["symbol"]!!.jsonPrimitive.content)
        assertEquals("Bearer agent-token", authHeader)
        assertEquals(1, api.stats.requests.get())
    }

    @Test
    fun `api errors carry the server's code and message`() = runTest {
        val api = client(backgroundScope, testScheduler.timeSource) {
            respond(
                """{"error":{"message":"Token has an invalid subject claim.","code":4105,"data":{},"requestId":"r1"}}""",
                HttpStatusCode.Unauthorized, json
            )
        }
        val error = assertFailsWith<ApiError> { api.get("register", Priority.INTERACTIVE) }
        assertEquals(401, error.status)
        assertEquals(4105, error.code)
        assertEquals("Token has an invalid subject claim.", error.apiMessage)
        assertEquals("r1", error.requestId)
        assertEquals(1, api.stats.errors.get())
    }

    @Test
    fun `a 429 is retried after the retry-after delay`() = runTest {
        var calls = 0
        val api = client(backgroundScope, testScheduler.timeSource) {
            calls++
            if (calls == 1) {
                respond(
                    """{"error":{"message":"slow down","code":429}}""", HttpStatusCode.TooManyRequests,
                    headersOf(HttpHeaders.RetryAfter to listOf("2"), HttpHeaders.ContentType to listOf("application/json"))
                )
            } else {
                respond("""{"data":{"ok":true}}""", HttpStatusCode.OK, json)
            }
        }
        val started = testScheduler.currentTime
        val data = api.get("my/ships", Priority.ACTION)
        assertTrue(data.jsonObject["ok"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(2, calls)
        assertEquals(1, api.stats.throttled.get())
        assertTrue(testScheduler.currentTime - started >= 2000, "waited for Retry-After")
    }

    @Test
    fun `server errors are retried and then given up on`() = runTest {
        var calls = 0
        val api = client(backgroundScope, testScheduler.timeSource) {
            calls++
            respond("""{"error":{"message":"boom","code":500}}""", HttpStatusCode.InternalServerError, json)
        }
        val error = assertFailsWith<ApiError> { api.get("systems/X1", Priority.BACKGROUND) }
        assertEquals(500, error.status)
        assertEquals(3, calls, "maxAttempts")
    }

    @Test
    fun `getAll follows paging until meta says it is done`() = runTest {
        val api = client(backgroundScope, testScheduler.timeSource) { request ->
            when (request.url.parameters["page"]) {
                "1" -> respond("""{"data":[{"n":1},{"n":2}],"meta":{"total":3,"page":1,"limit":2}}""", HttpStatusCode.OK, json)
                "2" -> respond("""{"data":[{"n":3}],"meta":{"total":3,"page":2,"limit":2}}""", HttpStatusCode.OK, json)
                else -> error("unexpected page ${request.url.parameters["page"]}")
            }
        }
        val items = api.getAll("my/ships", Priority.INTERACTIVE, limit = 2)
        assertEquals(listOf(1, 2, 3), items.map { it.jsonObject["n"]!!.jsonPrimitive.content.toInt() })
        assertEquals(2, api.stats.requests.get())
    }

    @Test
    fun `list responses without meta are taken as a single page`() = runTest {
        val api = client(backgroundScope, testScheduler.timeSource) {
            respond("""{"data":[{"n":1}]}""", HttpStatusCode.OK, json)
        }
        assertEquals(1, api.getAll("my/ships", Priority.INTERACTIVE).size)
        assertEquals(1, api.get("my/ships", Priority.INTERACTIVE).jsonArray.size)
    }
}
