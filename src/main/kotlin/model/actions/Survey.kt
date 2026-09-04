package model.actions

import kotlinx.serialization.Serializable
import model.extension.InstantSerializer
import model.market.TradeSymbol
import java.time.Instant

/**
 * A resource survey of an asteroid. Sent back verbatim with `extract/survey`, so nothing here may
 * be renamed or reordered; the server checks the [signature] against the whole object.
 */
@Serializable
data class Survey(
    val signature: String,
    val symbol: String,
    val deposits: List<SurveyDeposit>,
    @Serializable(with = InstantSerializer::class) val expiration: Instant,
    val size: SurveySize,
) {
    fun isValidAt(now: Instant): Boolean = expiration.isAfter(now)

    /** Share of extractions expected to come out as [good]: repeated deposits raise the odds. */
    fun chanceOf(good: TradeSymbol): Double =
        if (deposits.isEmpty()) 0.0 else deposits.count { it.symbol == good }.toDouble() / deposits.size

    val goods: Set<TradeSymbol> get() = deposits.map { it.symbol }.toSet()
}

@Serializable
data class SurveyDeposit(val symbol: TradeSymbol)

/** How many extractions a survey survives before it is exhausted. */
@Serializable
enum class SurveySize { SMALL, MODERATE, LARGE }
