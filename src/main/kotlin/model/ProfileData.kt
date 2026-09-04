package model

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import model.faction.FactionSymbol
import java.io.File

@Serializable
data class ProfileData(
    /** Agent symbol. On START it is kept in sync with the agent the token belongs to. */
    var name: String,
    val termWidth: Int,

    /** Faction a new agent registers with. It must be recruiting on the current server reset. */
    val faction: FactionSymbol = FactionSymbol.COSMIC,
)

/** Human-edited file, so keep it readable and keep defaults visible. */
private val ProfileJson = Json {
    prettyPrint = true
    encodeDefaults = true
}

fun saveProfile(path: String, data: ProfileData) {
    File(path).writeText(ProfileJson.encodeToString(data))
}

object Profile {
    lateinit var profileData: ProfileData

    fun createProfile(data: ProfileData) {
        profileData = data
    }
}
