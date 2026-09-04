package data

import io.github.oshai.kotlinlogging.KotlinLogging
import model.DEFAULT_PROF_DIR
import java.io.File

private val logger = KotlinLogging.logger {}

/** Sub-folders of the profile directory that hold one cached JSON file per entity. */
val PROFILE_CACHE_DIRS = listOf("agent", "markets", "waypoints", "shipyards", "systems")

/**
 * Creates every folder and placeholder file the client expects at runtime. Safe to call repeatedly.
 *
 * Nothing else in the code base creates these, and every boot path fails without them: the SQLite
 * driver will not create the database folder, and the profile caches are written with plain
 * `File.writeText`, which does not create parents.
 */
fun ensureRuntimeDirectories() {
    val dirs = listOf(File(DbClient.DATABASE_DIR)) + PROFILE_CACHE_DIRS.map { File(DEFAULT_PROF_DIR, it) }
    dirs.filterNot { it.isDirectory }.forEach { dir ->
        if (dir.mkdirs()) logger.info { "Created ${dir.path}" } else logger.warn { "Could not create ${dir.path}" }
    }

    val accountToken = File(ACCOUNT_TOKEN_FILE)
    if (!accountToken.exists()) {
        accountToken.parentFile?.mkdirs()
        accountToken.writeText("")
        logger.info {
            "Created empty $ACCOUNT_TOKEN_FILE. Paste an account token from https://my.spacetraders.io into it to register agents."
        }
    }
}
