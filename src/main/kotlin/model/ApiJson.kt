package model

import kotlinx.serialization.json.Json

/**
 * The single Json instance used for API responses, request bodies and on-disk caches.
 *
 * The API gains fields between versions (and between weekly resets) far more often than these
 * models are updated, so unknown keys are ignored rather than fatal. A null for a non-nullable
 * property that has a default falls back to that default, and absent nullable properties read
 * as null without needing an explicit default.
 */
val ApiJson: Json = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    explicitNulls = false
}
