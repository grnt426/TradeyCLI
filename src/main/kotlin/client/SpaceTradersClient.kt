package client

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.auth.*
import io.ktor.client.plugins.auth.providers.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.*
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import model.ApiJson
import model.extension.LastRead
import notification.NotificationManager
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.concurrent.timer
import kotlin.reflect.KSuspendFunction1
import kotlin.reflect.KSuspendFunction2

private val logger = KotlinLogging.logger {}
object SpaceTradersClient{

    lateinit var client: HttpClient

    /** Per-request budget. Navigation and market calls routinely take a few seconds; 2 s was too short. */
    const val REQUEST_TIMEOUT_MS = 15_000L

    var totalErrors = 0
    var throttled = 0

    val apiDispatcher = Dispatchers.IO

    val pendingRequestJobs = ConcurrentLinkedQueue<() -> Job>()

    var jobPressureWindow = IntArray(20) { 0 }
    var jobPressureUpdate = 0

    enum class JobPressure {
        LOW,
        OK,
        HIGH,
    }

    fun beginPollingRequests() {
        logger.info { "Processing job requests for ST" }
        timer("ApiRequestQueue", true, 0, 500) {
            if (jobPressureUpdate % 3 == 0) {
                jobPressureWindow = with(jobPressureWindow.drop(1).toMutableList()) {
                    add(pendingRequestJobs.size)
                    toIntArray()
                }
            }
            jobPressureUpdate = (jobPressureUpdate + 1) % 6
            if(pendingRequestJobs.isNotEmpty()) {
                println("${pendingRequestJobs.size} Enqueued. Executing")
                pendingRequestJobs.poll()().start()
            }
        }
    }

    /** Closes the HTTP client if one was ever created; the boot menu can be quit before that happens. */
    fun closeIfOpen() {
        if (::client.isInitialized) client.close()
    }

    fun createClient(authToken: String): HttpClient {
        client = HttpClient(CIO) {
            install(Auth) {
                bearer {
                    loadTokens {
                        BearerTokens(authToken, authToken)
                    }
                    // Send the token up front rather than waiting for a 401 challenge, which
                    // would otherwise cost two requests per call against the rate limit.
                    sendWithoutRequest { true }
                }
            }
            install(ContentNegotiation) {
                json(ApiJson)
            }
        }
        return client
    }

    /**
     * Blocking call
     */
    inline fun <reified T> callGet(request: HttpRequestBuilder): T? {
        var result: T? = null
        runBlocking {
            launch {
                try {
                    withTimeout(REQUEST_TIMEOUT_MS) {
                        val response = client.get(request)
                        if (response.status.isSuccess() && response.bodyAsText().isNotEmpty()) {
                            println(response.bodyAsText())
                            result = ApiJson.decodeFromString<JsonObject>(response.bodyAsText())["data"]?.let {
                                ApiJson.decodeFromJsonElement<T>(
                                    it
                                )
                            }!!
                            if (result is LastRead) {
                                (result as LastRead).lastRead = Instant.now()
                            }
                        } else {
                            totalErrors++
                            NotificationManager.errorNotification(
                                "HTTP ${response.status.value} for ${request.url.encodedPath}", response.bodyAsText()
                            )
                            println("${response.status} - ${response.bodyAsText()}")
                        }
                    }
                } catch (e: TimeoutCancellationException) {
                    NotificationManager.exceptNotification(
                        "Timeout", "Timeout calling ST", e
                    )
                    totalErrors++
                    println("Timeout")
                } catch (e: Exception) {
                    NotificationManager.exceptNotification(
                        "Exception", "General exception calling ST", e
                    )
                    totalErrors++
                }
            }
        }
        return result
    }

    inline fun <reified T> enqueueRequest(
        noinline callback: KSuspendFunction1<T, Unit>,
        noinline failback: KSuspendFunction2<HttpResponse?, Exception?, Unit>,
        request: HttpRequestBuilder
    ) {
        pendingRequestJobs.add {
            runBlocking(apiDispatcher) {
                launch {
                    try {
                        withTimeout(REQUEST_TIMEOUT_MS) {
                            val response = if (request.method == HttpMethod.Post) {
                                client.post(request)
                            }
                            else {
                                client.get(request)
                            }
                            if (response.status.isSuccess() && response.bodyAsText().isNotEmpty()) {
                                println("Success ${response.bodyAsText()}")
                                try {
                                    val result = ApiJson.decodeFromString<JsonObject>(response.bodyAsText())["data"]?.let {
                                        ApiJson.decodeFromJsonElement<T>(
                                            it
                                        )
                                    }!!
                                    if (result is LastRead) {
                                        (result as LastRead).lastRead = Instant.now()
                                    }
                                    try {
                                        callback(result)
                                    } catch (e: Exception) {
                                        // prevent exceptions in callback from triggering anything else
                                        NotificationManager.exceptNotification(
                                            "Callback Exception", "Failure in callback", e
                                        )
                                        totalErrors++
                                        println("Failure in handling callback")
                                        println(e.message)
                                        println(e.stackTraceToString())
                                    }
                                }
                                catch(e: SerializationException) {
                                    NotificationManager.exceptNotification(
                                        "Serialization Exception", "Failure deserializing response", e
                                    )
                                    totalErrors++
                                    println("Failure in parsing response of type ${T::class}")
                                    println(e.message)
                                    println(e.stackTraceToString())
                                }
                            } else {
                                totalErrors++

                                if (response.status == HttpStatusCode.TooManyRequests) {
                                    throttled++
                                    NotificationManager.errorNotification("Throttled")
                                } else {
                                    NotificationManager.errorNotification(
                                        "HTTP Error - ${response.status}", response.bodyAsText()
                                    )
                                }

                                failback(response, null)
                                println("Failure ${response.status} - ${response.bodyAsText()}")
                            }
                        }
                    } catch (e: TimeoutCancellationException) {
                        NotificationManager.exceptNotification(
                            "Timeout", "Timeout calling ST", e
                        )
                        totalErrors++
                        println("Exception caught??")
                        failback(null, e)
                    }
                }
            }
        }
    }

    fun enqueueFafRequest(request: HttpRequestBuilder) {
        pendingRequestJobs.add {
            runBlocking(apiDispatcher) {
                launch {
                    try {
                        withTimeout(REQUEST_TIMEOUT_MS) {
                            val response = if (request.method == HttpMethod.Post) {
                                client.post(request)
                            }
                            else {
                                client.get(request)
                            }
                            if (response.status.isSuccess() && response.bodyAsText().isNotEmpty()) {
                                println("Success ${response.bodyAsText()}")

                            } else {
                                totalErrors++
                                println("Failure ${response.status} - ${response.bodyAsText()}")
                            }
                        }
                    } catch (e: TimeoutCancellationException) {
                        totalErrors++
                        println("Exception caught??")
                    }
                }
            }
        }
    }

    suspend fun ignoredCallback(any: Any) {
    }

    suspend fun ignoredFailback(resp: HttpResponse?, ex: Exception?) {
        val msg = resp?.bodyAsText() ?: if (ex != null) {
            ex.message
        } else {
            "No data"
        }
        NotificationManager.errorNotification(
            "IE: $msg", "BAD BAD BAD"
        )
    }

    fun getJobPressure(): JobPressure {
        return with(pendingRequestJobs.size) {
            return@with getJobPressure(this)
        }
    }

    fun getJobPressure(amount: Int): JobPressure {
        return with(amount) {
            if (this <= 7)
                JobPressure.LOW
            else if (this <= 15)
                JobPressure.OK
            else
                JobPressure.HIGH
        }
    }
}