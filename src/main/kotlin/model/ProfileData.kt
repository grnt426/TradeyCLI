package model

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import model.faction.FactionSymbol
import java.io.File

const val DEFAULT_PROF_DIR = "profile"
const val DEFAULT_PROF_FILE = "$DEFAULT_PROF_DIR/profile.settings.json"

@Serializable
data class ProfileData(
    /** The active agent. Its token and data live under `profile/agents/<name>/`. */
    var name: String,
    val termWidth: Int,

    /** Faction a new agent registers with. It must be recruiting on the current server reset. */
    val faction: FactionSymbol = FactionSymbol.COSMIC,
)

/** Human-edited file, so keep it readable and keep defaults visible. */
private val ProfileJson = Json {
    prettyPrint = true
    encodeDefaults = true
    ignoreUnknownKeys = true
}

fun loadProfile(path: String = DEFAULT_PROF_FILE): ProfileData =
    ProfileJson.decodeFromString(File(path).readText())

fun saveProfile(path: String, data: ProfileData) {
    File(path).writeText(ProfileJson.encodeToString(data))
}

object Profile {
    lateinit var profileData: ProfileData

    fun createProfile(data: ProfileData) {
        profileData = data
    }
}
