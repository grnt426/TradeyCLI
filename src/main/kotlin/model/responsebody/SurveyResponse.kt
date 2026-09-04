package model.responsebody

import kotlinx.serialization.Serializable
import model.actions.Survey
import model.ship.Cooldown

@Serializable
data class SurveyResponse(
    val cooldown: Cooldown,
    val surveys: List<Survey>,
)
