package model

import kotlinx.serialization.Serializable

@Serializable
data class ProfileData(var name: String, val termWidth: Int)

object Profile {
    lateinit var profileData: ProfileData

    fun createProfile(data: ProfileData) {
        profileData = data
    }
}