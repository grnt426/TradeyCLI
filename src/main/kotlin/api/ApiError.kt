package api

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import model.ApiJson

/**
 * A non-success response from the API, carrying the server's own error code and message from the
 * `{"error": {...}}` envelope. `api-docs/live/error-codes.json` lists the codes; the ones the verb
 * layer acts on are in [ApiErrorCodes].
 */
class ApiError(
    val status: Int,
    val code: Int,
    val apiMessage: String,
    val path: String,
    val requestId: String? = null,

    /** The error's `data` element when present, such as the cooldown on a 4000. */
    val data: JsonElement? = null,
) : Exception("HTTP $status on $path: $apiMessage (code $code)")

/** Error codes the client reacts to rather than just reporting. */
object ApiErrorCodes {
    const val COOLDOWN_CONFLICT = 4000
    const val NAVIGATE_IN_TRANSIT = 4200
    const val NAVIGATE_INSUFFICIENT_FUEL = 4203
    const val NAVIGATE_SAME_DESTINATION = 4204
    const val SHIP_IN_TRANSIT = 4214
    const val PURCHASE_SHIP_CREDITS = 4216
    const val SURVEY_EXPIRED = 4221
    const val SURVEY_EXHAUSTED = 4224
    const val CARGO_FULL = 4228
    const val SHIP_NOT_IN_ORBIT = 4236
    const val SHIP_NOT_DOCKED = 4244
    const val EXTRACT_DESTABILIZED = 4253
    const val WAYPOINT_NO_YIELD = 4261
    const val MARKET_NOT_SOLD = 4602
    const val MARKET_UNIT_LIMIT = 4604
}

/** Builds an [ApiError] from a response body, tolerating bodies that are not the standard envelope. */
fun apiErrorFrom(status: Int, path: String, body: String): ApiError {
    val error: JsonObject? = runCatching { ApiJson.parseToJsonElement(body).jsonObject["error"]?.jsonObject }.getOrNull()
    if (error == null) {
        return ApiError(status, -1, body.take(300).ifBlank { "empty response body" }, path)
    }
    return ApiError(
        status = status,
        code = error["code"]?.jsonPrimitive?.content?.toIntOrNull() ?: -1,
        apiMessage = error["message"]?.jsonPrimitive?.content ?: body.take(300),
        path = path,
        requestId = error["requestId"]?.jsonPrimitive?.content,
        data = error["data"],
    )
}
