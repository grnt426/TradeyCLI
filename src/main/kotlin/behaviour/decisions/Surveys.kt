package behaviour.decisions

import model.actions.Survey

object Surveys {
    /** Extracting with a survey is assumed to yield this much more; the simulator uses the same number. */
    const val YIELD_BONUS = 1.5

    /**
     * The survey worth using for [plan], or null when plain extraction pays as well. A survey pays
     * off when the goods it promises are worth more at the market than the rock's average mix.
     */
    fun pick(surveys: List<Survey>, plan: MiningPlan): Survey? {
        if (surveys.isEmpty()) return null
        val plain = plan.valuePerUnit * plan.tradedShare
        return surveys
            .map { it to valueOf(it, plan) }
            .filter { (_, value) -> value * YIELD_BONUS > plain }
            .maxByOrNull { (_, value) -> value }
            ?.first
    }

    /** Credits per extracted unit a survey should produce, counting only what the market buys. */
    fun valueOf(survey: Survey, plan: MiningPlan): Double =
        survey.goods.sumOf { good -> survey.chanceOf(good) * (plan.prices[good] ?: 0) }
}
