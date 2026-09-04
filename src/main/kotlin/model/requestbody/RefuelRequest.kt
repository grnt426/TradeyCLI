package model.requestbody

import kotlinx.serialization.Serializable

/** Both fields optional: with neither, the ship fills its tank. */
@Serializable
data class RefuelRequest(
    val units: Int? = null,
    val fromCargo: Boolean? = null,
)
