package data

import kotlinx.serialization.Serializable

@Serializable
abstract class LastRead(var timestamp: String = "")
