package data

import java.io.File

/** Bearer token for the current agent, issued by the register call. Dies with each server reset. */
const val AGENT_TOKEN_FILE = "profile/authtoken.secret"

/** Account token from https://my.spacetraders.io. Needed to register agents; survives resets. */
const val ACCOUNT_TOKEN_FILE = "profile/accounttoken.secret"

/** Returns the trimmed contents of a secret file, or null when the file is missing or blank. */
fun readSecret(path: String): String? {
    val file = File(path)
    if (!file.isFile) return null
    return file.readText().trim().ifEmpty { null }
}
