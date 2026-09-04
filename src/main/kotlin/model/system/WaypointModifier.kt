package model.system

import kotlinx.serialization.Serializable

/**
 * A modifier applied to a waypoint, such as UNSTABLE or CRITICAL_LIMIT on an over-mined asteroid.
 * The symbol set changes between API versions, so it is kept as text; see [WaypointModifiers].
 */
@Serializable
data class WaypointModifier(
    val symbol: String,
    val name: String = "",
    val description: String = "",
)

/** The modifier symbols the API documents (api-docs/spec/models/WaypointModifierSymbol.json). */
object WaypointModifiers {
    const val STRIPPED = "STRIPPED"
    const val UNSTABLE = "UNSTABLE"
    const val RADIATION_LEAK = "RADIATION_LEAK"
    const val CRITICAL_LIMIT = "CRITICAL_LIMIT"
    const val CIVIL_UNREST = "CIVIL_UNREST"

    fun of(symbol: String): WaypointModifier = WaypointModifier(symbol, symbol.lowercase().replaceFirstChar { it.uppercase() }, "")
}
