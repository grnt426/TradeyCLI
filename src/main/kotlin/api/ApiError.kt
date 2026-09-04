package api

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import model.ApiJson

/**
 * A non-success response from the API, carrying the server's own error code and message from the
 * `{"error": {...}}` envelope. `GET /error-codes` lists the codes.
 */
class ApiError(
    val status: Int,
    val code: Int,
    val apiMessage: String,
    val path: String,
    val requestId: String? = null,
) : Exception("HTTP $status on $path: $apiMessage (code $code)")

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
    )
}
