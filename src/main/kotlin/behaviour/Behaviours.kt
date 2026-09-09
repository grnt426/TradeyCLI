package behaviour

import engine.Snapshot
import model.ship.Ship

/** How a behaviour is called: name, parameters, a check against the world, and the code. */
class BehaviourSpec(
    val name: String,
    val description: String,
    val params: List<ParamSpec>,
    val validate: (snapshot: Snapshot, ship: Ship, params: Map<String, String>) -> List<String>,
    val run: suspend BehaviourScope.() -> Unit,
)

class ParamSpec(val name: String, val description: String, val required: Boolean = false)

/** Every behaviour the plan may name. */
object Behaviours {
    val all: Map<String, BehaviourSpec> = listOf(probeMarketsSpec, mineAndSellSpec, tradeSpec, expandSpec, runContractSpec, feedSpec, supplyGateSpec, chartSystemSpec, exploreSpec, surveySpec, mineInPlaceSpec, collectSpec, pioneerSpec, parkSpec, warpChartSpec, donateWarpDriveSpec, refitWarpSpec, warpTradeSpec).associateBy { it.name }

    fun get(name: String): BehaviourSpec? = all[name]

    /** What a ship does when nobody says: probes read prices, ships with a real hold trade, small miners mine. */
    fun defaultFor(ship: Ship): String? = when {
        !ship.usesFuel -> "probeMarkets"
        // A surveyor drone has the mount and no hold: its job is the rounds.
        ship.canSurvey && ship.cargo.capacity == 0 -> "survey"
        ship.cargo.capacity >= 30 -> "trade"
        ship.canMine || ship.canSiphon -> "mineAndSell"
        else -> null
    }

    fun usage(): String = all.values.joinToString("\n") { spec ->
        val params = spec.params.joinToString(" ") { p -> if (p.required) "--${p.name} X" else "[--${p.name} X]" }
        "  ${spec.name} $params\n      ${spec.description}\n" + spec.params.joinToString("\n") { p -> "      --${p.name}: ${p.description}" }
    }
}
