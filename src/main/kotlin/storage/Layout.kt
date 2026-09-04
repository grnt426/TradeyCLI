package storage

import model.DEFAULT_PROF_DIR
import java.io.File

/**
 * Where things live on disk.
 *
 * ```
 * profile/
 *   profile.settings.json        terminal width, faction to register with, active agent
 *   accounttoken.secret          account token (optional; only NEW needs it)
 *   agents/
 *     <SYMBOL>/
 *       authtoken.secret         the agent's token; dies with each reset
 *       data-<resetDate>.db      everything known about this agent on that reset
 *       archive/                 databases from earlier resets
 * ```
 *
 * Tokens and databases are git-ignored. The rate limit is per account, so the pacer is per
 * process, not per agent folder.
 */
object Layout {
    val profileDir: File get() = File(DEFAULT_PROF_DIR)
    val agentsDir: File get() = File(profileDir, "agents")

    fun agentDir(symbol: String): File = File(agentsDir, symbol.uppercase())

    fun agentTokenFile(symbol: String): File = File(agentDir(symbol), "authtoken.secret")

    fun databaseFile(symbol: String, resetDate: String): File = File(agentDir(symbol), "data-$resetDate.db")

    fun archiveDir(symbol: String): File = File(agentDir(symbol), "archive")

    /** The plan: which ship runs which behaviour. Edited by `assign` and `unassign`. */
    fun planFile(symbol: String): File = File(agentDir(symbol), "plan.json")

    /** The newest per-reset database of an agent, or null when it has never booted. */
    fun latestDatabase(symbol: String): File? =
        agentDir(symbol).listFiles { f -> f.isFile && f.name.startsWith("data-") && f.name.endsWith(".db") }?.maxByOrNull { it.name }

    fun resetDateOf(database: File): String = database.name.removePrefix("data-").removeSuffix(".db")

    /** Agents that have a folder, whether or not a token is present. */
    fun listAgents(): List<String> =
        agentsDir.listFiles()?.filter { it.isDirectory }?.map { it.name }?.sorted() ?: emptyList()

    fun saveAgentToken(symbol: String, token: String) {
        val file = agentTokenFile(symbol)
        file.parentFile.mkdirs()
        file.writeText(token)
    }
}
