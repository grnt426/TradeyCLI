package graph

import model.ship.Ship
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue

class GraphSearch {

    private val threadPool = Executors.newFixedThreadPool(4)
    private val buildQueue = LinkedBlockingQueue<Pair<Int, String>>()
    private val searchRequests = mutableListOf<SearchRequest>()

    init {
        for (i in 0..3)
            threadPool.execute(GraphBuilder())
    }

    fun buildMapping(fuel: Int, system: String) {

    }

    fun enqueueFindWay(ship: Ship, start: String, end: String, found: (Set<String>) -> Unit, fail: () -> Unit) {

    }

    class GraphBuilder : Runnable {
        override fun run() {
            TODO("Not yet implemented")
        }

    }
}