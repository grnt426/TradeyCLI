package model.requestbody

import kotlinx.serialization.Serializable

/** The module to install on or remove from a ship, by its trade symbol. */
@Serializable
data class ModuleRequest(val symbol: String)
