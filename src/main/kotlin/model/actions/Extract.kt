package model.actions

import kotlinx.serialization.Serializable
import model.ship.Cooldown
import model.ship.components.Cargo

@Serializable
data class Extract(
    val extraction: Extraction,
    val cooldown: Cooldown,
    val cargo: Cargo,
)