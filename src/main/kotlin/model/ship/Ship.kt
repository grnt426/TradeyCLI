package model.ship

import Symbol
import kotlinx.serialization.Serializable
import model.extension.LastRead
import model.market.TradeSymbol
import model.ship.components.*

@Serializable
data class Ship(
    override val symbol: String,
    val nav: Navigation,
    val crew: Crew,
    val fuel: Fuel,
    val cooldown: Cooldown,
    val frame: Frame,
    val engine: Engine,
    val reactor: Reactor,
    val modules: List<Module>,
    val mounts: List<Mount>,
    val registration: Registration,
    val cargo: Cargo,
) : Symbol, LastRead() {

    val isDocked: Boolean get() = nav.status == ShipNavStatus.DOCKED
    val isInOrbit: Boolean get() = nav.status == ShipNavStatus.IN_ORBIT
    val cargoFull: Boolean get() = cargo.units >= cargo.capacity
    val cargoSpaceLeft: Int get() = cargo.capacity - cargo.units

    /** Probes have no tank and fly for free; everything else pays fuel for distance. */
    val usesFuel: Boolean get() = fuel.capacity > 0

    fun hasMount(prefix: String): Boolean = mounts.any { it.symbol.name.startsWith(prefix) }
    val canMine: Boolean get() = hasMount("MOUNT_MINING_LASER")
    val canSurvey: Boolean get() = hasMount("MOUNT_SURVEYOR")
    val canSiphon: Boolean get() = hasMount("MOUNT_GAS_SIPHON")

    /** Summed strength of the mining lasers; the simulator's yield knob. */
    val miningStrength: Long get() = mounts.filter { it.symbol.name.startsWith("MOUNT_MINING_LASER") }.sumOf { it.strength }

    /** Summed strength of the gas siphons. */
    val siphonStrength: Long get() = mounts.filter { it.symbol.name.startsWith("MOUNT_GAS_SIPHON") }.sumOf { it.strength }

    fun unitsOf(good: TradeSymbol): Int = cargo.unitsOf(good)
}
