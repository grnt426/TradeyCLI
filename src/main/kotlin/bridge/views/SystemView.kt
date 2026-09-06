package bridge.views

import bridge.BridgeModel
import bridge.Format
import bridge.canvas.Attr
import bridge.canvas.Len
import bridge.canvas.Painter
import bridge.canvas.Rect
import bridge.glyphs.Atlas
import bridge.glyphs.Palette
import bridge.scene.Table
import model.system.Waypoint

/**
 * The system screen: the map, a list of the system's waypoints, and a card for whichever
 * waypoint or ship is selected. Selecting in the list highlights on the map; clicking the map
 * moves the list.
 */
class SystemView : WidgetView() {
    override val title = "System"
    private var model: BridgeModel? = null
    private var selectedWaypoint: String? = null
    private var selectedShip: String? = null
    private var selectedStar = false

    private val map: SystemMap = SystemMap(
        model = { model!! },
        onSelectWaypoint = { selectedWaypoint = it; selectedShip = null; selectedStar = false; model?.selectedWaypoint = it; it?.let(list::selectKey) },
        onSelectShip = { selectedShip = it; selectedStar = false; model?.selectedShip = it },
        onSelectStar = { selectedShip = null; selectedStar = true },
    )
    private val list: Table = Table(
        columns = listOf(
            Table.Column("waypoint", 12),
            Table.Column("type", 15),
            Table.Column("has"),
            Table.Column("ships", 5, alignRight = true),
        ),
        onSelect = { row: Table.Row? ->
            if (row != null) {
                selectedWaypoint = row.key
                selectedShip = null
                selectedStar = false
                map.selectedWaypoint = row.key
                model?.selectedWaypoint = row.key
            }
        },
        onActivate = { row: Table.Row -> map.centreOnKey(row.key) },
    )

    override fun paint(p: Painter, model: BridgeModel, t: Double) {
        this.model = model
        beginFrame()
        val snap = model.snapshot()
        val now = model.now()
        model.selectedSystem?.let { chosen ->
            model.selectedSystem = null
            if (chosen != map.system) {
                map.system = chosen
                map.fit()
                selectedWaypoint = null
                selectedShip = null
                selectedStar = false
            }
        }
        val sys = map.system ?: snap.hqSystem
        val waypoints = sys?.let { snap.waypointsIn(it) } ?: emptyList()

        val sideWidth = (p.width / 3).coerceIn(36, 50)
        val (mapRect, side) = Rect(0, 0, p.width, p.height).cols(Len.weight(), Len.fixed(sideWidth))
        val (listRect, cardRect) = side.rows(Len.weight(), Len.fixed(16))

        val parked = snap.ships.values.filter { it.nav.systemSymbol == sys }.groupBy { it.nav.waypointSymbol }
        list.setRows(waypoints.map { wp ->
            val has = buildList {
                if (wp.hasMarket) add("market")
                if (wp.hasShipyard) add("shipyard")
                if (wp.isUnderConstruction) add("gate site")
                if (wp.isMineable) add(if (snap.validSurveysFor(wp.symbol, now).isNotEmpty()) "ore surveyed" else "ore")
                if (wp.isSiphonable) add("gas")
                if (wp.orbits != null) add("orbits ${wp.orbits.substringAfterLast('-')}")
            }.joinToString(" ")
            Table.Row(wp.symbol, listOf(wp.symbol, wp.type.name.lowercase().replace('_', ' '), has, (parked[wp.symbol]?.size ?: 0).let { if (it == 0) "" else it.toString() }), Atlas.waypoint(wp.type).colour)
        })
        map.selectedWaypoint = selectedWaypoint
        map.selectedShip = selectedShip
        map.selectedStar = selectedStar

        place(map, p.panel(mapRect, sys ?: "System", focus === map), t)
        place(list, p.panel(listRect, "Waypoints (${waypoints.size})", focus === list, hint = "↑↓ · double-click centres"), t)
        val ship = selectedShip?.let { snap.ships[it] }
        val wp = selectedWaypoint?.let { snap.waypoints[it] }
        when {
            selectedStar && sys != null -> starCard(p.panel(cardRect, sys), sys, model)
            ship != null -> shipCard(p.panel(cardRect, ship.symbol), ship, model)
            wp != null -> waypointCard(p.panel(cardRect, wp.symbol), wp, model)
            else -> p.panel(cardRect, "Selection").text(0, 0, "click a waypoint or a ship", Palette.textDim)
        }
        endFrame(listOf(map, list))
    }

    /** The star is not a waypoint: nothing docks there. The card says what the system is instead. */
    private fun starCard(p: Painter, sys: String, model: BridgeModel) {
        val snap = model.snapshot()
        val system = snap.systems[sys]
        var y = 0
        val colour = Atlas.star(system?.type ?: "")
        p.put(0, y, Atlas.STAR, colour, null, Attr.BOLD)
        p.text(2, y, (system?.type ?: "star").lowercase().replace('_', ' '), colour, null, Attr.BOLD)
        if (system != null) p.textRight(p.width, y, "sector ${system.sectorSymbol} · ${system.x}, ${system.y}", Palette.textDim)
        y++
        p.text(0, y++, "the system's star; waypoints orbit it, ships cannot visit it", Palette.textDim)
        y++
        val waypoints = snap.waypointsIn(sys)
        val counts = waypoints.groupingBy { it.type }.eachCount().entries.sortedByDescending { it.value }
            .joinToString(", ") { "${it.value} ${it.key.name.lowercase().replace('_', ' ')}" }
        for (line in bridge.scene.TextBlock.wrap("${waypoints.size} waypoints: $counts", p.width).take(3)) p.text(0, y++, line, Palette.text)
        val gate = waypoints.firstOrNull { it.type.name.endsWith("GATE") }
        if (gate != null) p.text(0, y++, "jump gate ${gate.symbol}" + (if (gate.isUnderConstruction) ", under construction" else ""), Palette.accent)
        p.text(0, y++, "${waypoints.count { it.hasMarket }} markets, ${waypoints.count { it.hasShipyard }} shipyards, ${waypoints.count { it.isMineable }} minable", Palette.text)
        if (system != null && system.factions.isNotEmpty()) p.text(0, y++, "factions: ${system.factions.joinToString { it.symbol.toString() }}".take(p.width), Palette.textDim)
        val here = snap.ships.values.count { it.nav.systemSymbol == sys }
        p.text(0, y, "$here of ${snap.ships.size} ships in this system", Palette.textDim)
    }

    private fun waypointCard(p: Painter, wp: Waypoint, model: BridgeModel) {
        val snap = model.snapshot()
        val now = model.now()
        var y = 0
        val glyph = Atlas.waypoint(wp.type)
        p.put(0, y, glyph.ch, glyph.colour, null, Attr.BOLD)
        p.text(2, y, wp.type.name.lowercase().replace('_', ' '), glyph.colour, null, Attr.BOLD)
        p.textRight(p.width, y++, "${wp.x}, ${wp.y}", Palette.textDim)
        wp.orbits?.let { p.text(0, y++, "orbits $it", Palette.textDim) }
        if (wp.orbitals.isNotEmpty()) p.text(0, y++, "${wp.orbitals.size} orbitals: ${wp.orbitals.joinToString(" ") { it.symbol.substringAfterLast('-') }}".take(p.width), Palette.textDim)
        val traits = wp.traits.map { it.name }.filter { it.isNotBlank() }
        if (traits.isNotEmpty()) {
            for (line in bridge.scene.TextBlock.wrap(traits.joinToString(", "), p.width).take(3)) p.text(0, y++, line, Palette.text)
        }
        if (wp.isUnderConstruction) p.text(0, y++, "under construction", Palette.accent)
        val market = snap.markets[wp.symbol]
        if (market != null) {
            val read = if (market.hasPrices) "read ${Format.age(market.lastRead, now)} ago" else "prices not read"
            p.text(0, y++, "market: ${market.imports.size} imports, ${market.exports.size} exports, $read".take(p.width), Palette.info)
        }
        if (wp.hasShipyard) p.text(0, y++, "shipyard" + (snap.shipyards[wp.symbol]?.let { ": ${it.shipTypes.size} types" } ?: ""), Palette.info)
        val here = snap.ships.values.filter { it.nav.waypointSymbol == wp.symbol && !it.nav.inTransitAt(now) }.sortedBy { it.symbol }
        if (here.isNotEmpty() && y < p.height) {
            p.text(0, y++, "ships here:", Palette.textDim)
            here.take(p.height - y).forEach { s ->
                p.put(1, y, '◆', Atlas.role(s.registration.role))
                p.text(3, y++, "${s.symbol}  ${snap.shipStatus[s.symbol]?.let { "${it.behaviour}: ${it.phase}" } ?: s.nav.status.name.lowercase()}".take(p.width - 3), Palette.text)
            }
        }
    }

    private fun shipCard(p: Painter, ship: model.ship.Ship, model: BridgeModel) {
        val snap = model.snapshot()
        val now = model.now()
        var y = 0
        p.put(0, y, '◆', Atlas.role(ship.registration.role), null, Attr.BOLD)
        p.text(2, y, ship.registration.role.name, Atlas.role(ship.registration.role), null, Attr.BOLD)
        p.textRight(p.width, y++, ship.frame.name.removePrefix("Frame "), Palette.textDim)
        snap.shipStatus[ship.symbol]?.let { p.text(0, y++, "${it.behaviour}: ${it.phase} ${it.detail}".trim().take(p.width), Palette.text) }
        val r = ship.nav.route
        if (ship.nav.inTransitAt(now)) {
            p.text(0, y++, "${r.origin.symbol} → ${r.destination.symbol}".take(p.width), Palette.info)
            p.text(0, y++, "arrives in ${Format.clock(java.time.Duration.between(now, r.arrival))}  ${ship.nav.flightMode.name.lowercase()}", Palette.text)
        } else {
            p.text(0, y++, "${ship.nav.status.name.lowercase().replace('_', ' ')} at ${ship.nav.waypointSymbol}", Palette.text)
        }
        if (ship.fuel.capacity > 0) p.text(0, y++, "fuel ${ship.fuel.current}/${ship.fuel.capacity}", Palette.textDim)
        p.text(0, y++, "hold ${ship.cargo.units}/${ship.cargo.capacity}" + ship.cargo.inventory.take(3).joinToString("") { "  ${it.symbol.name.take(10)} ${it.units}" }.take(p.width), Palette.textDim)
    }
}
