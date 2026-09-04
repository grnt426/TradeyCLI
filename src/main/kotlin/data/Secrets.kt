package data

import java.io.File

/** Account token from https://my.spacetraders.io (account settings). Needed to register agents; survives resets. */
const val ACCOUNT_TOKEN_FILE = "profile/accounttoken.secret"

/** Where the agent token lived before agents got their own folders. Honoured once, then moved. */
const val LEGACY_AGENT_TOKEN_FILE = "profile/authtoken.secret"

/** Returns the trimmed contents of a secret file, or null when the file is missing or blank. */
fun readSecret(path: String): String? {
    val file = File(path)
    if (!file.isFile) return null
    return file.readText().trim().ifEmpty { null }
}
