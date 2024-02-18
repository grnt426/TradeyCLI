package model

import kotlinx.serialization.Serializable

@Serializable
data class Location(
    var symbol: String,
    val type: String,
    val systemSymbol: String,
    val x: Long,
    val y: Long
)
