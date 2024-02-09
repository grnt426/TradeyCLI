package client

import model.LastRead
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import java.io.File
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.concurrent.timer
import kotlin.reflect.KSuspendFunction1
import kotlin.reflect.KSuspendFunction2

object SpaceTradersClient{

    lateinit var client: HttpClient

    val apiDispatcher = Dispatchers.IO

    val pendingRequestJobs = ConcurrentLinkedQueue<() -> Job>()

    fun beginPollingRequests() {
        timer("ApiRequestQueue", true, 0, 500) {
            if(pendingRequestJobs.isNotEmpty()) {
                println("${pendingRequestJobs.size} Enqueued. Executing")
                pendingRequestJobs.poll()().start()
            }
        }
    }

    fun createClient(authFile: String): HttpClient {
        client = HttpClient(CIO) {
            install(Auth) {
                bearer {
                    loadTokens {
                        val token = File(authFile).readText()
                        BearerTokens(token, token)
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
                                (result as LastRead).timestamp = LocalDateTime.now().toString()
                            }
                        } else {
                            println("${response.status} - ${response.bodyAsText()}")
                        }
                    }
                } catch (e: TimeoutCancellationException) {
                    println("Timeout")
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
                                val result = Json.decodeFromString<JsonObject>(response.bodyAsText())["data"]?.let {
                                    Json.decodeFromJsonElement<T>(
                                        it
                                    )
                                }!!
                                if (result is LastRead) {
                                    (result as LastRead).timestamp = LocalDateTime.now().toString()
                                }
                                try {
                                    callback(result)
                                }
                                catch(e: Exception) {
                                    // prevent exceptions in callback from triggering anything else
                                }
                            } else {
                                failback(response, null)
                                println("Failure ${response.status} - ${response.bodyAsText()}")
                            }
                        }
                    } catch (e: TimeoutCancellationException) {
                        println("Exception caught??")
                        failback(null, e)
                    }
                }
            }
        }
    }

    suspend fun ignoredCallback(any: Any) {

    }

    suspend fun ignoredFailback(resp: HttpResponse?, ex: Exception?) {

    }
}