package startup

import api.ApiClient
import api.ApiError
import api.SpaceTradersApi
import app.App
import data.ACCOUNT_TOKEN_FILE
import data.LEGACY_AGENT_TOKEN_FILE
import data.ensureRuntimeDirectories
import data.readSecret
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import model.Agent
import model.BootProgress
import model.DEFAULT_PROF_FILE
import model.ProfileData
import model.exceptions.BootFailure
import model.loadProfile
import model.saveProfile
import storage.Layout
import java.io.File

private val logger = KotlinLogging.logger {}

object BootManager {

    /**
     * Boots the engine for [agentSymbol] (default: the active agent in the profile file) using the
     * token in its folder. A token left at the pre-folder location `profile/authtoken.secret` is
     * accepted once and moved into place.
     *
     * @throws BootFailure with a message for the user when the token is missing or rejected.
     */
    suspend fun normalStart(
        agentSymbol: String? = null,
        agentTokenPath: String? = null,
        progress: (String) -> Unit = BootProgress::step,
    ) {
        ensureRuntimeDirectories()
        val settings = loadProfile()
        App.profData = settings
        val symbol = (agentSymbol ?: settings.name).uppercase()
        val tokenFile = agentTokenPath?.let(::File) ?: Layout.agentTokenFile(symbol)
        val legacyFile = File(LEGACY_AGENT_TOKEN_FILE)
        val fromLegacy = !tokenFile.isFile && legacyFile.isFile
        val token = readSecret(tokenFile.path) ?: readSecret(legacyFile.path) ?: throw BootFailure(
            "No agent token at ${tokenFile.path}. Mint one for $symbol at https://my.spacetraders.io and paste " +
                    "it into that file, or type NEW to register a new agent with an account token."
        )

        boot(token, progress) { agent ->
            if (fromLegacy || !Layout.agentTokenFile(agent.symbol).isFile) {
                Layout.saveAgentToken(agent.symbol, token)
                if (fromLegacy && legacyFile.delete()) logger.info { "Moved ${legacyFile.path} into ${Layout.agentDir(agent.symbol).path}" }
            }
            // Only a boot that did not name its agent may change the profile's default; `--agent X` is a one-off.
            if (agentSymbol == null && !settings.name.equals(agent.symbol, ignoreCase = true)) {
                logger.info { "Active agent is now ${agent.symbol} (profile said '${settings.name}')" }
                settings.name = agent.symbol
                saveProfile(DEFAULT_PROF_FILE, settings)
            }
        }
    }

    /**
     * Registers a new agent with the symbol and faction from the profile file, using the account
     * token in [ACCOUNT_TOKEN_FILE], saves its token into the agent's folder and boots it.
     *
     * @throws BootFailure with a message for the user when a prerequisite is missing or the API
     * rejects the registration.
     */
    suspend fun bootstrapNew(
        accountTokenPath: String = ACCOUNT_TOKEN_FILE,
        progress: (String) -> Unit = BootProgress::step,
    ) {
        ensureRuntimeDirectories()
        val accountToken = readSecret(accountTokenPath) ?: throw BootFailure(
            "No account token. Create one under account settings at https://my.spacetraders.io and paste it into " +
                    "$accountTokenPath on a single line."
        )
        val settings = loadProfile()
        App.profData = settings
        if (settings.name.length !in 3..14) throw BootFailure(
            "Agent symbol '${settings.name}' must be 3 to 14 characters; edit 'name' in $DEFAULT_PROF_FILE."
        )

        logger.info { "Registering agent ${settings.name} with faction ${settings.faction}" }
        progress("Registering ${settings.name} with ${settings.faction}")
        val (symbol, token) = registerAgent(accountToken, settings)
        Layout.saveAgentToken(symbol, token)
        settings.name = symbol
        saveProfile(DEFAULT_PROF_FILE, settings)
        logger.info { "Registered agent $symbol; token saved to ${Layout.agentTokenFile(symbol).path}" }

        boot(token, progress) {}
    }

    /**
     * Registers [symbol] with [faction] using the account token and saves the agent token in its
     * folder. Does not boot and does not change the active agent in the profile. Returns the
     * symbol the server assigned.
     */
    suspend fun registerOnly(symbol: String, faction: model.faction.FactionSymbol, accountTokenPath: String = ACCOUNT_TOKEN_FILE): String {
        ensureRuntimeDirectories()
        val accountToken = readSecret(accountTokenPath) ?: throw BootFailure("No account token in $accountTokenPath.")
        if (symbol.length !in 3..14) throw BootFailure("Agent symbol '$symbol' must be 3 to 14 characters.")
        val (assigned, token) = registerAgent(accountToken, ProfileData(symbol, 160, faction))
        Layout.saveAgentToken(assigned, token)
        logger.info { "Registered agent $assigned with $faction; token saved to ${Layout.agentTokenFile(assigned).path}" }
        return assigned
    }

    /** Identical to [normalStart] while ship automation is disabled; kept so the menu entry works. */
    suspend fun debugStart() {
        normalStart()
    }

    private suspend fun boot(token: String, progress: (String) -> Unit, onAgentKnown: suspend (Agent) -> Unit) {
        try {
            App.engine.boot(token, progress, onAgentKnown)
        } catch (e: ApiError) {
            throw BootFailure(
                "The API rejected the request: ${e.apiMessage} (HTTP ${e.status}, code ${e.code}). " +
                        "If the server has reset since the token was issued, mint a new one."
            )
        }
    }

    /** Returns the new agent's symbol and token. The token is the only thing that must not be lost. */
    private suspend fun registerAgent(accountToken: String, settings: ProfileData): Pair<String, String> {
        val data = try {
            ApiClient(accountToken, App.engine.pacer).use { client ->
                SpaceTradersApi(client).register(settings.name, settings.faction).jsonObject
            }
        } catch (e: ApiError) {
            val hint = if (e.apiMessage.contains("agent-token")) {
                " That is an agent token. Agent tokens belong in profile/agents/<SYMBOL>/authtoken.secret; " +
                        "put it there and use START. Only an account token from the portal's account settings can register agents."
            } else ""
            throw BootFailure("Registration rejected (HTTP ${e.status}): ${e.apiMessage} (code ${e.code}).$hint")
        }
        val token = data["token"]?.jsonPrimitive?.content
        val symbol = data["agent"]?.jsonObject?.get("symbol")?.jsonPrimitive?.content
        if (token == null || symbol == null) {
            logger.error { "Registration response lacked token or agent symbol: $data" }
            throw BootFailure("Registration response lacked the token or agent symbol; see log.txt.")
        }
        return symbol to token
    }
}
