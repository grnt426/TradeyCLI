package bridge

import bridge.canvas.Rgb
import bridge.glyphs.Palette
import engine.Event

/** Every engine event as one feed line: who, what, how much. */
object EventLines {
    fun describe(e: Event): Pair<String, Rgb> = when (e) {
        is Event.Booted -> "welcome, ${e.agent}; reset ${e.resetDate}" to Palette.good
        is Event.ShipsLoaded -> "${e.count} ships loaded" to Palette.textDim
        is Event.SystemLoaded -> "${e.system}: ${e.waypoints} waypoints, ${e.markets} markets, ${e.shipyards} shipyards" to Palette.textDim
        is Event.MarketUpdated -> "${e.symbol} prices read" to Palette.textDim
        is Event.Warning -> e.message to Palette.warn
        is Event.Failure -> e.message to Palette.bad
        is Event.PhaseChanged -> "${e.ship} ${e.behaviour}: ${e.phase} ${e.detail}".trimEnd() to Palette.text
        is Event.BehaviourStarted -> "${e.ship} starts ${e.behaviour}" to Palette.info
        is Event.BehaviourFinished -> "${e.ship} finished ${e.behaviour}" to Palette.info
        is Event.BehaviourFailed -> "${e.ship} ${e.behaviour} failed: ${e.reason}" + (e.restartIn?.let { ", restart in $it" } ?: "") to Palette.bad
        is Event.Extracted -> "${e.ship} mined ${e.units} ${e.good} at ${e.waypoint} (${e.cargo})" to Palette.text
        is Event.Sold -> "${e.ship} sold ${e.units} ${e.good} at ${e.waypoint} +${Format.credits(e.credits)}" to Palette.good
        is Event.Bought -> "${e.ship} bought ${e.units} ${e.good} at ${e.waypoint} -${Format.credits(e.credits)}" to Palette.warn
        is Event.Refueled -> "${e.ship} refueled ${e.units} at ${e.waypoint} -${Format.credits(e.credits)}" to Palette.textDim
        is Event.Surveyed -> "${e.ship} surveyed ${e.waypoint}: ${e.surveys} surveys" to Palette.text
        is Event.ShipPurchased -> "bought ${e.ship}, a ${e.type}, for ${Format.credits(e.credits)}" to Palette.accent
        is Event.Charted -> "${e.ship} charted ${e.waypoint} +${Format.credits(e.credits)}" to Palette.good
        is Event.ContractOffered -> "contract ${e.id} offered: ${e.type} for ${Format.credits(e.payment)}" to Palette.info
        is Event.Delivered -> "${e.ship} delivered ${e.units} ${e.good} for ${e.contract}" to Palette.text
        is Event.ContractFulfilled -> "contract ${e.id} fulfilled +${Format.credits(e.credits)}" to Palette.good
        is Event.Supplied -> "${e.ship} supplied ${e.units} ${e.good} to ${e.site}, ${e.remaining} to go" to Palette.accent
        is Event.Jumped -> "${e.ship} jumped to ${e.waypoint}, antimatter ${e.antimatterCost}" to Palette.info
        is Event.PhaseAdvanced -> "phase ${e.phase}: ${e.description}" to Palette.accent
        is Event.Activity -> "${e.ship} ${e.kind} ${e.detail}, ${e.seconds / 60} min" to (if (e.kind == "drift") Palette.warn else Palette.text)
        is Event.Transferred -> "${e.to} took ${e.units} ${e.good} from ${e.from}" to Palette.text
        is Event.Notable -> e.text to tone(e.kind)
    }

    /** The colour of a notable event by kind: a gate opening is good news, the plan's moves carry the accent, a new system the info blue. */
    fun tone(kind: String): Rgb = when (kind) {
        "gate" -> Palette.good
        "plan" -> Palette.accent
        "system" -> Palette.info
        else -> Palette.text
    }
}
