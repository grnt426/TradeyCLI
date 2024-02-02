package data

import kotlinx.serialization.Serializable

@Serializable
data class ProfileData(val name: String, val termWidth: Int)

object Profile {
    lateinit var profileData: ProfileData

    fun createProfile(data: ProfileData) {
        profileData = data
    }
}