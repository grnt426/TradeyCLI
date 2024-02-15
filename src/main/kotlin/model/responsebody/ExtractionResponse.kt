package model.responsebody

import kotlinx.serialization.Serializable
import model.actions.Extraction
import model.ship.Cooldown
import model.ship.components.Cargo

@Serializable
data class ExtractionResponse(
    val extraction: Extraction,
    val cooldown: Cooldown,
    val cargo: Cargo,
)