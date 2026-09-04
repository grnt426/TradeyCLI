package model

import kotlinx.serialization.Serializable

/** `GET /`: the only unwrapped response in the API. Tells us which reset we are on. */
@Serializable
data class ServerStatus(
    val status: String = "",
    val version: String = "",
    val resetDate: String,
    val serverResets: ServerResets? = null,
)

@Serializable
data class ServerResets(val next: String = "", val frequency: String = "")
