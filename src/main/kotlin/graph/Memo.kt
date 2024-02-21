package graph

import kotlinx.serialization.Serializable

@Serializable
data class Memo(val fuel: Int, val system: String, val cached: Map<String, Set<String>>)