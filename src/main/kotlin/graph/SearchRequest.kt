package graph

import model.ship.Ship

data class SearchRequest(
    val ship: Ship,
    val start: String,
    val end: String,
    val found: (Set<String>) -> Unit,
    val fail: () -> Unit
)