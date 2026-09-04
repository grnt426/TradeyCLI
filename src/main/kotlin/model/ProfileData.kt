package model

import kotlinx.serialization.Serializable
import model.faction.FactionSymbol

@Serializable
data class ProfileData(
    /** Agent symbol. Used as the callsign when registering; 3 to 14 characters, upper-cased by the server. */
    var name: String,
    val termWidth: Int,

    /** Faction a new agent registers with. It must be recruiting on the current server reset. */
    val faction: FactionSymbol = FactionSymbol.COSMIC,
)

object Profile {
    lateinit var profileData: ProfileData

    fun createProfile(data: ProfileData) {
        profileData = data
    }
}
