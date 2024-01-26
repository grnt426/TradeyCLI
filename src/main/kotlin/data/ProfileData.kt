package data

import kotlinx.serialization.Serializable

@Serializable
data class ProfileData(val name: String, val termWidth: Int)
