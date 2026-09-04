package data

import io.github.oshai.kotlinlogging.KotlinLogging
import storage.Layout
import java.io.File

private val logger = KotlinLogging.logger {}

/**
 * Creates the folders and placeholder files the client expects at runtime. Safe to call
 * repeatedly. Per-agent folders are created by the engine when an agent is identified.
 */
fun ensureRuntimeDirectories() {
    listOf(Layout.profileDir, Layout.agentsDir).filterNot { it.isDirectory }.forEach { dir ->
        if (dir.mkdirs()) logger.info { "Created ${dir.path}" } else logger.warn { "Could not create ${dir.path}" }
    }

    val accountToken = File(ACCOUNT_TOKEN_FILE)
    if (!accountToken.exists()) {
        accountToken.writeText("")
        logger.info {
            "Created empty $ACCOUNT_TOKEN_FILE. Paste an account token from https://my.spacetraders.io into it to register agents."
        }
    }
}
