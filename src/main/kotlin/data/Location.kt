package data

import kotlinx.serialization.Serializable

@Serializable
data class Location(
    val symbol: String,
    val type: String,
    val systemSymbol: String,
    val x: Long,
    val y: Long
)
