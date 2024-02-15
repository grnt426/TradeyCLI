package model.system

import BROWN_EXCAVATOR_COLOR
import kotlinx.serialization.Serializable
import java.awt.Color

@Serializable
enum class WaypointType(@Transient val color: Color) {
    PLANET(Color.GREEN),
    GAS_GIANT(Color.ORANGE),
    MOON(Color.GRAY),
    ORBITAL_STATION(Color.BLUE),
    JUMP_GATE(Color.MAGENTA),
    ASTEROID_FIELD(BROWN_EXCAVATOR_COLOR),
    ASTEROID(Color.DARK_GRAY),
    ENGINEERED_ASTEROID(Color.DARK_GRAY),
    ASTEROID_BASE(Color.DARK_GRAY),
    NEBULA(Color.PINK),
    DEBRIS_FIELD(BROWN_EXCAVATOR_COLOR),
    GRAVITY_WELL(Color.WHITE),
    ARTIFICIAL_GRAVITY_WELL(Color.CYAN),
    FUEL_STATION(Color.YELLOW),
    EMPTY(Color.black)
}