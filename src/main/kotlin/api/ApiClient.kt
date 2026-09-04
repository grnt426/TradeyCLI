package api

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.*
import io.ktor.client.engine.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import model.ApiJson
import java.io.Closeable
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}

/** Paging information the list endpoints return next to `data`. */
@Serializable
data class Meta(val total: Int, val page: Int, val limit: Int)

class ApiStats {
    val requests = AtomicInteger()
    val errors = AtomicInteger()
    val throttled = AtomicInteger()
    val retries = AtomicInteger()
}

data class RetryPolicy(
    val maxAttempts: Int = 4,
    val baseDelay: Duration = 1.seconds,
    val maxDelay: Duration = 30.seconds,
) {
    fun backoff(attempt: Int): Duration = (baseDelay * (1 shl (attempt - 1).coerceIn(0, 10))).coerceAtMost(maxDelay)
}

/**
 * The one way out to the API. Every call goes through the [pacer], carries the bearer token, is
 * retried on 429 and 5xx, and comes back as the `data` element of the response envelope or an
 * [ApiError]. Endpoint-specific typing lives in [SpaceTradersApi].
 */
class ApiClient(
    token: String,
    val pacer: RequestPacer,
    engine: HttpClientEngine = CIO.create(),
    baseUrl: String = BASE_URL,
    private val retry: RetryPolicy = RetryPolicy(),
) : Closeable {

    val stats = ApiStats()

    @PublishedApi
    internal val http: HttpClient = HttpClient(engine) {
        expectSuccess = false
        install(ContentNegotiation) { json(ApiJson) }
        install(HttpTimeout) {
            requestTimeoutMillis = 15_000
            connectTimeoutMillis = 10_000
        }
        defaultRequest {
            url(baseUrl)
            header(HttpHeaders.Authorization, "Bearer $token")
        }
    }

    class Envelope(val data: JsonElement, val meta: Meta?)

    suspend fun get(path: String, priority: Priority, params: Map<String, String> = emptyMap()): JsonElement =
        envelope(path, priority) {
            http.get(path) { params.forEach { (k, v) -> parameter(k, v) } }
        }.data

    /** Follows `meta` paging until every item of a list endpoint has been collected. */
    suspend fun getAll(path: String, priority: Priority, limit: Int = 20): List<JsonElement> {
        val items = mutableListOf<JsonElement>()
        var page = 1
        while (true) {
            val envelope = envelope(path, priority) {
                http.get(path) {
                    parameter("page", page)
                    parameter("limit", limit)
                }
            }
            items.addAll(envelope.data.jsonArray)
            val meta = envelope.meta ?: break
            if (page * meta.limit >= meta.total) break
            page++
        }
        return items
    }

    suspend fun post(path: String, priority: Priority): JsonElement =
        envelope(path, priority) { http.post(path) }.data

    suspend inline fun <reified B : Any> post(path: String, body: B, priority: Priority): JsonElement =
        envelope(path, priority) {
            http.post(path) {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        }.data

    @PublishedApi
    internal suspend fun envelope(path: String, priority: Priority, send: suspend () -> HttpResponse): Envelope {
        var attempt = 0
        while (true) {
            attempt++
            pacer.acquire(priority)
            stats.requests.incrementAndGet()
            val response = try {
                send()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                stats.errors.incrementAndGet()
                if (attempt >= retry.maxAttempts) throw e
                val wait = retry.backoff(attempt)
                logger.warn { "$path attempt $attempt failed (${e.message}); retrying in $wait" }
                stats.retries.incrementAndGet()
                delay(wait)
                continue
            }
            val body = response.bodyAsText()
            val status = response.status.value
            when {
                response.status.isSuccess() -> return parseEnvelope(path, status, body)

                status == 429 -> {
                    stats.throttled.incrementAndGet()
                    if (attempt >= retry.maxAttempts) throw apiErrorFrom(status, path, body)
                    val wait = retryAfter(response) ?: retry.backoff(attempt)
                    logger.warn { "$path throttled (attempt $attempt); waiting $wait" }
                    stats.retries.incrementAndGet()
                    delay(wait)
                }

                status >= 500 -> {
                    stats.errors.incrementAndGet()
                    if (attempt >= retry.maxAttempts) throw apiErrorFrom(status, path, body)
                    val wait = retry.backoff(attempt)
                    logger.warn { "$path returned $status (attempt $attempt); retrying in $wait" }
                    stats.retries.incrementAndGet()
                    delay(wait)
                }

                else -> {
                    stats.errors.incrementAndGet()
                    throw apiErrorFrom(status, path, body)
                }
            }
        }
    }

    private fun parseEnvelope(path: String, status: Int, body: String): Envelope {
        val root = runCatching { ApiJson.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: throw ApiError(status, -1, "response was not a JSON object: ${body.take(200)}", path)
        val data = root["data"] ?: throw ApiError(status, -1, "response had no data element", path)
        val meta = root["meta"]?.let { runCatching { ApiJson.decodeFromJsonElement<Meta>(it) }.getOrNull() }
        return Envelope(data, meta)
    }

    private fun retryAfter(response: HttpResponse): Duration? =
        response.headers[HttpHeaders.RetryAfter]?.trim()?.toDoubleOrNull()?.let { (it * 1000).toLong().let { ms -> Duration.parse("${ms}ms") } }

    override fun close() {
        http.close()
    }

    companion object {
        const val BASE_URL = "https://api.spacetraders.io/v2/"
    }
}
