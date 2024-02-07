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
import model.actions.Extract
import model.system.Waypoint
import java.io.File
import java.lang.Thread.sleep
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.concurrent.timer
import kotlin.math.min

object SpaceTradersClient{

    lateinit var client: HttpClient

    val apiDispatcher = Dispatchers.IO

    val pendingRequests = ConcurrentLinkedQueue<() -> Job>()

    val pendingRequestsAny = ConcurrentLinkedQueue<RequestContext<*>>()

    class RequestContext<Any>(val callback: (Any) -> Unit, val request: HttpRequestBuilder, var attempts: Int = 0)

    fun setup() {

        val mineRequest = RequestContext<Extract>({ t -> println("")}, request {})
        val wayRequest = RequestContext<Waypoint>({ t -> println("")}, request {})


        timer("ApiRequestQueue", true, 0, 500) {
            println("Available to execute")
            if(pendingRequests.isNotEmpty()) {
                println("Executing")
                pendingRequests.poll()().start()
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

    inline fun <reified T> callGet(request: HttpRequestBuilder): T? {
        var result: T? = null
        runBlocking {
            launch {
                try {
//                    withTimeout(2_000) {
                        val response = client.get(request)
                        if (response.status == HttpStatusCode.OK && response.bodyAsText().isNotEmpty()) {
                            println(response.bodyAsText())
                            result = Json.decodeFromString<JsonObject>(response.bodyAsText())["data"]?.let {
                                Json.decodeFromJsonElement<T>(
                                    it
                                )
                            }!!
                            if (result is LastRead) {
                                (result!! as LastRead).timestamp = LocalDateTime.now().toString()
                            }
                        } else {
                            println("${response.status} - ${response.bodyAsText()}")
                        }
//                    }
                } catch (e: TimeoutCancellationException) {
                    println("Timeout")
                }
            }
        }
        return result
    }

    inline fun <reified T> asyncGet(crossinline callback: (T) -> Unit, request: HttpRequestBuilder) {
        pendingRequests.add {
            runBlocking {
                withContext(apiDispatcher) {
                    launch {
                        try {
                            withTimeout(350) {
                                val response = client.get(request)
                                if (response.status == HttpStatusCode.OK && response.bodyAsText().isNotEmpty()) {
                                    println(response.bodyAsText())
                                    val result =
                                        Json.decodeFromString<JsonObject>(response.bodyAsText())["data"]?.let {
                                            Json.decodeFromJsonElement<T>(
                                                it
                                            )
                                        }!!
                                    if (result is LastRead) {
                                        (result as LastRead).timestamp = LocalDateTime.now().toString()
                                    }
                                    callback(result)
                                } else {
                                    println("${response.status} - ${response.bodyAsText()}")
//                                    asyncGet<T>(callback, request)
                                }
                            }
                        } catch (e: TimeoutCancellationException) {
                            println("Timeout")
                        }
                    }
                }
            }
        }
    }

    inline fun <reified T> asyncGet(requestContext: RequestContext<T>) {
        pendingRequests.add {
            runBlocking {
                withContext(apiDispatcher) {
                    launch {
                        try {
                            withTimeout(350) {
                                val response = client.get(requestContext.request)
                                if (response.status == HttpStatusCode.OK && response.bodyAsText().isNotEmpty()) {
                                    println(response.bodyAsText())
                                    val result =
                                        Json.decodeFromString<JsonObject>(response.bodyAsText())["data"]?.let {
                                            Json.decodeFromJsonElement<T>(
                                                it
                                            )
                                        }!!
                                    if (result is LastRead) {
                                        (result as LastRead).timestamp = LocalDateTime.now().toString()
                                    }
                                    requestContext.callback(result)
                                } else {
                                    println("${response.status} - ${response.bodyAsText()}")
//                                    asyncGet<T>(callback, request)
                                }
                            }
                        } catch (e: TimeoutCancellationException) {
                            println("Timeout")
                        }
                    }
                }
            }
        }
    }
}