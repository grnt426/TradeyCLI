package bridge.glyphs

import bridge.canvas.Rgb
import model.ship.ShipRole
import model.system.WaypointType

/**
 * The glyph atlas: what each kind of thing looks like on the map. One place to iterate on, and
 * every glyph one column wide. Stars are drawn from their type name because the system's type is
 * a wider enum than the map needs.
 */
object Atlas {
    data class Glyph(val ch: Char, val colour: Rgb)

    fun waypoint(type: WaypointType): Glyph = when (type) {
        WaypointType.PLANET -> Glyph('●', Rgb(96, 176, 128))
        WaypointType.GAS_GIANT -> Glyph('◍', Rgb(224, 150, 84))
        WaypointType.MOON -> Glyph('○', Rgb(156, 160, 178))
        WaypointType.ORBITAL_STATION -> Glyph('■', Rgb(104, 164, 232))
        WaypointType.JUMP_GATE -> Glyph('◎', Rgb(204, 112, 240))
        WaypointType.ASTEROID_FIELD -> Glyph('∴', Rgb(152, 124, 92))
        WaypointType.ASTEROID -> Glyph('∙', Rgb(134, 124, 112))
        WaypointType.ENGINEERED_ASTEROID -> Glyph('◊', Rgb(176, 152, 120))
        WaypointType.ASTEROID_BASE -> Glyph('▣', Rgb(176, 144, 112))
        WaypointType.NEBULA -> Glyph('≈', Rgb(220, 124, 172))
        WaypointType.DEBRIS_FIELD -> Glyph('∷', Rgb(140, 120, 100))
        WaypointType.GRAVITY_WELL -> Glyph('◌', Rgb(200, 204, 224))
        WaypointType.ARTIFICIAL_GRAVITY_WELL -> Glyph('◌', Rgb(104, 220, 220))
        WaypointType.FUEL_STATION -> Glyph('▲', Rgb(232, 204, 96))
    }

    fun role(role: ShipRole): Rgb = when (role) {
        ShipRole.COMMAND -> Rgb(236, 96, 88)
        ShipRole.EXCAVATOR -> Rgb(196, 140, 80)
        ShipRole.HAULER, ShipRole.TRANSPORT -> Rgb(96, 200, 132)
        ShipRole.SATELLITE, ShipRole.EXPLORER, ShipRole.SURVEYOR -> Rgb(200, 128, 240)
        ShipRole.REFINERY, ShipRole.FABRICATOR -> Rgb(232, 200, 96)
        ShipRole.HARVESTER -> Rgb(120, 200, 200)
        else -> Rgb(180, 190, 210)
    }

    /** The star itself. Geometric Shapes, not Dingbats: `★` renders wider than a cell in some fonts. */
    const val STAR = '◉'

    /** The colour of a star from its type name (BLUE_STAR, RED_STAR, ...). */
    fun star(typeName: String): Rgb = when {
        "BLUE" in typeName -> Rgb(160, 190, 255)
        "RED" in typeName -> Rgb(255, 120, 96)
        "ORANGE" in typeName -> Rgb(255, 176, 96)
        "WHITE" in typeName -> Rgb(240, 240, 255)
        "YOUNG" in typeName -> Rgb(200, 220, 255)
        "NEUTRON" in typeName -> Rgb(200, 240, 255)
        "BLACK" in typeName -> Rgb(120, 100, 160)
        "HYPERGIANT" in typeName -> Rgb(255, 200, 140)
        else -> Rgb(255, 224, 140)
    }
}
