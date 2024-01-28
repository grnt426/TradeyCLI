package client

import data.LastRead
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.auth.*
import io.ktor.client.plugins.auth.providers.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import java.io.File
import java.time.LocalDateTime

object SpaceTradersClient{

    lateinit var client: HttpClient
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

    inline fun <reified T: LastRead> callGet(request: HttpRequestBuilder): T? {
        var result: T? = null
        runBlocking {
            launch {
                try {
                    withTimeout(2_000) {
                        val response = client.get(request)
                        if (response.status == HttpStatusCode.OK && response.bodyAsText().isNotEmpty()) {
                            println(response.bodyAsText())
                            result = Json.decodeFromString<JsonObject>(response.bodyAsText())["data"]?.let {
                                Json.decodeFromJsonElement<T>(
                                    it
                                )
                            }!!
                            result!!.timestamp = LocalDateTime.now().toString()
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
}