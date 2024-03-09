package client

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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import model.extension.LastRead
import notification.NotificationManager
import java.io.File
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.concurrent.timer
import kotlin.reflect.KSuspendFunction1
import kotlin.reflect.KSuspendFunction2

object SpaceTradersClient{

    lateinit var client: HttpClient

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

    fun createClient(authFile: File): HttpClient = createClient(authFile.readText())

    fun createClient(authToken: String): HttpClient {
        client = HttpClient(CIO) {
            install(Auth) {
                bearer {
                    loadTokens {
                        BearerTokens(authToken, authToken)
                    }
                }
            }
            install(ContentNegotiation) {
                json()
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
                    withTimeout(2_000) {
                        val response = client.get(request)
                        if (response.status.isSuccess() && response.bodyAsText().isNotEmpty()) {
                            println(response.bodyAsText())
                            result = Json.decodeFromString<JsonObject>(response.bodyAsText())["data"]?.let {
                                Json.decodeFromJsonElement<T>(
                                    it
                                )
                            }!!
                            if (result is LastRead) {
                                (result as LastRead).lastRead = Instant.now()
                            }
                        } else {
                            totalErrors++
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
        callback: KSuspendFunction1<T, Unit>,
        failback: KSuspendFunction2<HttpResponse?, Exception?, Unit>,
        request: HttpRequestBuilder
    ) {
        pendingRequestJobs.add {
            runBlocking(apiDispatcher) {
                launch {
                    try {
                        withTimeout(2_000) {
                            val response = if (request.method == HttpMethod.Post) {
                                client.post(request)
                            }
                            else {
                                client.get(request)
                            }
                            if (response.status.isSuccess() && response.bodyAsText().isNotEmpty()) {
                                println("Success ${response.bodyAsText()}")
                                try {
                                    val result = Json.decodeFromString<JsonObject>(response.bodyAsText())["data"]?.let {
                                        Json.decodeFromJsonElement<T>(
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
                        withTimeout(2_000) {
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