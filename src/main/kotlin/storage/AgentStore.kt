package storage

import api.RequestRecord
import behaviour.decisions.CreditPoint
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import model.Agent
import model.ApiJson
import model.Shipyard
import model.market.Market
import model.market.MarketTradeGood
import model.market.MarketTransaction
import model.market.TradeSymbol
import model.ship.Ship
import model.system.OrbitalNames
import model.system.System
import model.system.Waypoint
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.upsert
import java.io.Closeable
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.sql.Connection
import java.time.Instant
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

private val logger = KotlinLogging.logger {}

data class PriceObservation(val good: MarketTradeGood, val observedAt: Instant)

data class Checkpoint(val id: String, val behaviour: String, val entity: String?, val phase: String, val params: String, val updatedAt: Instant, val detail: String = "")

data class ExtractionRecord(
    val ship: String,
    val waypoint: String,
    val good: TradeSymbol,
    val units: Int,
    val surveySignature: String?,
    val modifiers: List<String>,
    val at: Instant,
)

/**
 * Everything known about one agent on one server reset, in one SQLite file. All access goes
 * through a single thread, so there is exactly one writer and callers never block each other in
 * the database; they queue. Reads and writes are `suspend` and cheap to call from the engine.
 */
class AgentStore private constructor(
    val agentSymbol: String,
    val resetDate: String,
    val file: File,
    private val db: Database,
    private val executor: ExecutorService,
) : Closeable {

    private val dispatcher = executor.asCoroutineDispatcher()

    private suspend fun <T> tx(block: Transaction.() -> T): T = withContext(dispatcher) {
        transaction(db) { block() }
    }

    // Agent

    suspend fun putAgent(agent: Agent) = tx { put(AgentTable, agent.symbol, ApiJson.encodeToString(agent)) }

    suspend fun getAgent(): Agent? = tx { AgentTable.selectAll().firstOrNull()?.let { decode<Agent>(it[AgentTable.json]) } }

    // Fleet

    suspend fun putShips(ships: List<Ship>) = tx {
        ShipTable.deleteAll()
        ships.forEach { put(ShipTable, it.symbol, ApiJson.encodeToString(it)) }
    }

    suspend fun putShip(ship: Ship) = tx { put(ShipTable, ship.symbol, ApiJson.encodeToString(ship)) }

    suspend fun listShips(): List<Ship> = tx { ShipTable.selectAll().map { decode<Ship>(it[ShipTable.json]) } }

    // Universe

    suspend fun putSystem(system: System) = tx { put(SystemTable, system.symbol, ApiJson.encodeToString(system)) }

    suspend fun listSystems(): List<System> = tx { SystemTable.selectAll().map { decode<System>(it[SystemTable.json]) } }

    suspend fun putWaypoints(waypoints: List<Waypoint>) = tx {
        waypoints.forEach { put(WaypointTable, it.symbol, ApiJson.encodeToString(it), it.systemSymbol) }
    }

    suspend fun listWaypoints(system: String? = null): List<Waypoint> = tx {
        val query = WaypointTable.selectAll()
        if (system != null) query.where { WaypointTable.systemSymbol eq system }
        query.map { decode<Waypoint>(it[WaypointTable.json]) }
    }

    /** Stores the market and appends its current prices to the history. */
    suspend fun putMarket(market: Market, observedAt: Instant = Instant.now()) = tx {
        put(MarketTable, market.symbol, ApiJson.encodeToString(market), OrbitalNames.getSectorSystem(market.symbol))
        if (market.tradeGoods.isNotEmpty()) {
            PriceTable.batchInsert(market.tradeGoods) { good ->
                this[PriceTable.marketSymbol] = market.symbol
                this[PriceTable.tradeSymbol] = good.symbol.name
                this[PriceTable.type] = good.type.name
                this[PriceTable.supply] = good.supply.name
                this[PriceTable.activity] = good.activity?.name
                this[PriceTable.purchasePrice] = good.purchasePrice
                this[PriceTable.sellPrice] = good.sellPrice
                this[PriceTable.tradeVolume] = good.tradeVolume
                this[PriceTable.observedAt] = observedAt.toEpochMilli()
            }
        }
    }

    suspend fun listMarkets(system: String? = null): List<Market> = tx {
        val query = MarketTable.selectAll()
        if (system != null) query.where { MarketTable.systemSymbol eq system }
        query.map { decode<Market>(it[MarketTable.json]) }
    }

    suspend fun putShipyard(shipyard: Shipyard) = tx {
        put(ShipyardTable, shipyard.symbol, ApiJson.encodeToString(shipyard), OrbitalNames.getSectorSystem(shipyard.symbol))
    }

    suspend fun listShipyards(system: String? = null): List<Shipyard> = tx {
        val query = ShipyardTable.selectAll()
        if (system != null) query.where { ShipyardTable.systemSymbol eq system }
        query.map { decode<Shipyard>(it[ShipyardTable.json]) }
    }

    // History

    suspend fun priceHistory(market: String, good: TradeSymbol): List<PriceObservation> = tx {
        PriceTable.selectAll()
            .where { (PriceTable.marketSymbol eq market) and (PriceTable.tradeSymbol eq good.name) }
            .orderBy(PriceTable.observedAt, SortOrder.ASC)
            .map { row ->
                PriceObservation(
                    MarketTradeGood(
                        symbol = TradeSymbol.valueOf(row[PriceTable.tradeSymbol]),
                        type = enumValueOf(row[PriceTable.type]),
                        tradeVolume = row[PriceTable.tradeVolume],
                        supply = enumValueOf(row[PriceTable.supply]),
                        purchasePrice = row[PriceTable.purchasePrice],
                        sellPrice = row[PriceTable.sellPrice],
                        activity = row[PriceTable.activity]?.let { enumValueOf(it) },
                    ),
                    Instant.ofEpochMilli(row[PriceTable.observedAt]),
                )
            }
    }

    suspend fun putTransaction(t: MarketTransaction) = tx {
        TransactionTable.insert {
            it[shipSymbol] = t.shipSymbol
            it[waypointSymbol] = t.waypointSymbol
            it[tradeSymbol] = t.tradeSymbol.name
            it[type] = t.type.name
            it[units] = t.units
            it[pricePerUnit] = t.pricePerUnit
            it[totalPrice] = t.totalPrice
            it[timestamp] = t.timestamp
        }
    }

    suspend fun listTransactions(since: Instant? = null): List<MarketTransaction> = tx {
        val query = TransactionTable.selectAll()
        if (since != null) query.where { TransactionTable.timestamp greaterEq since.toString() }
        query.orderBy(TransactionTable.id, SortOrder.ASC).map { row ->
            MarketTransaction(
                shipSymbol = row[TransactionTable.shipSymbol],
                waypointSymbol = row[TransactionTable.waypointSymbol],
                tradeSymbol = TradeSymbol.valueOf(row[TransactionTable.tradeSymbol]),
                type = enumValueOf(row[TransactionTable.type]),
                units = row[TransactionTable.units],
                pricePerUnit = row[TransactionTable.pricePerUnit],
                totalPrice = row[TransactionTable.totalPrice],
                timestamp = row[TransactionTable.timestamp],
            )
        }
    }

    suspend fun putExtraction(record: ExtractionRecord) = tx {
        ExtractionTable.insert {
            it[shipSymbol] = record.ship
            it[waypointSymbol] = record.waypoint
            it[tradeSymbol] = record.good.name
            it[units] = record.units
            it[surveySignature] = record.surveySignature
            it[modifiers] = record.modifiers.joinToString(",").take(200)
            it[at] = record.at.toEpochMilli()
        }
    }

    suspend fun listExtractions(waypoint: String? = null): List<ExtractionRecord> = tx {
        val query = ExtractionTable.selectAll()
        if (waypoint != null) query.where { ExtractionTable.waypointSymbol eq waypoint }
        query.orderBy(ExtractionTable.at, SortOrder.ASC).map { row ->
            ExtractionRecord(
                ship = row[ExtractionTable.shipSymbol],
                waypoint = row[ExtractionTable.waypointSymbol],
                good = TradeSymbol.valueOf(row[ExtractionTable.tradeSymbol]),
                units = row[ExtractionTable.units],
                surveySignature = row[ExtractionTable.surveySignature],
                modifiers = row[ExtractionTable.modifiers].split(',').filter { it.isNotBlank() },
                at = Instant.ofEpochMilli(row[ExtractionTable.at]),
            )
        }
    }

    suspend fun logRequest(record: RequestRecord) = tx {
        RequestLogTable.insert {
            it[at] = java.lang.System.currentTimeMillis()
            it[path] = record.path.take(200)
            it[priority] = record.priority.name
            it[status] = record.status
            it[durationMs] = record.durationMs
            it[attempt] = record.attempt
        }
    }

    suspend fun requestCount(): Long = tx { RequestLogTable.selectAll().count() }

    // Checkpoints (used by the scripting overhaul)

    suspend fun listCheckpoints(): List<Checkpoint> = tx {
        CheckpointTable.selectAll().map { row ->
            Checkpoint(row[CheckpointTable.id], row[CheckpointTable.behaviour], row[CheckpointTable.entity], row[CheckpointTable.phase], row[CheckpointTable.params], Instant.ofEpochMilli(row[CheckpointTable.updatedAt]), row[CheckpointTable.detail] ?: "")
        }
    }

    suspend fun putCredits(at: Instant, credits: Long) = tx {
        CreditsTable.insert {
            it[CreditsTable.at] = at.toEpochMilli()
            it[CreditsTable.credits] = credits
        }
    }

    suspend fun listCredits(since: Instant): List<CreditPoint> = tx {
        CreditsTable.selectAll().where { CreditsTable.at greaterEq since.toEpochMilli() }.orderBy(CreditsTable.at, SortOrder.ASC)
            .map { CreditPoint(Instant.ofEpochMilli(it[CreditsTable.at]), it[CreditsTable.credits]) }
    }

    suspend fun deleteCheckpoint(id: String) = tx { CheckpointTable.deleteWhere { CheckpointTable.id eq id } }

    suspend fun putCheckpoint(id: String, behaviour: String, entity: String?, phase: String, params: String, detail: String = "", at: Instant = Instant.now()) = tx {
        CheckpointTable.upsert {
            it[CheckpointTable.id] = id
            it[CheckpointTable.behaviour] = behaviour
            it[CheckpointTable.entity] = entity
            it[CheckpointTable.phase] = phase
            it[CheckpointTable.params] = params
            it[CheckpointTable.detail] = detail
            it[updatedAt] = at.toEpochMilli()
        }
    }

    override fun close() {
        executor.shutdown()
        TransactionManager.closeAndUnregister(db)
    }

    private fun Transaction.put(table: EntityTable, symbol: String, json: String, system: String? = null) {
        table.upsert {
            it[table.symbol] = symbol
            it[table.json] = json
            it[table.fetchedAt] = java.lang.System.currentTimeMillis()
            if (table is SystemScopedTable && system != null) it[table.systemSymbol] = system
        }
    }

    private inline fun <reified T> decode(json: String): T = ApiJson.decodeFromString(json)

    companion object {
        const val SCHEMA_VERSION = 1

        /**
         * Opens (creating if needed) the database for [agentSymbol] on [resetDate], moving the
         * databases of earlier resets into `archive/` first.
         */
        fun open(agentDir: File, agentSymbol: String, resetDate: String): AgentStore {
            agentDir.mkdirs()
            archiveOlderResets(agentDir, resetDate)
            val file = File(agentDir, "data-$resetDate.db")
            val fresh = !file.exists()
            val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "storage-$agentSymbol").apply { isDaemon = true } }
            val db = Database.connect(
                url = "jdbc:sqlite:${file.path}",
                driver = "org.sqlite.JDBC",
                setupConnection = { connection ->
                    connection.createStatement().use { s ->
                        s.execute("PRAGMA journal_mode=WAL")
                        s.execute("PRAGMA synchronous=NORMAL")
                        s.execute("PRAGMA busy_timeout=5000")
                    }
                    connection.transactionIsolation = Connection.TRANSACTION_SERIALIZABLE
                },
            )
            transaction(db) {
                SchemaUtils.createMissingTablesAndColumns(*ALL_TABLES.toTypedArray())
                MetaTable.upsert { it[key] = "agent"; it[value] = agentSymbol }
                MetaTable.upsert { it[key] = "resetDate"; it[value] = resetDate }
                MetaTable.upsert { it[key] = "schemaVersion"; it[value] = SCHEMA_VERSION.toString() }
            }
            logger.info { (if (fresh) "Created " else "Opened ") + file.path }
            return AgentStore(agentSymbol, resetDate, file, db, executor)
        }

        private fun archiveOlderResets(agentDir: File, resetDate: String) {
            val current = "data-$resetDate.db"
            val older = agentDir.listFiles { f -> f.isFile && f.name.startsWith("data-") && f.name != current }
                ?: return
            if (older.isEmpty()) return
            val archive = File(agentDir, "archive").apply { mkdirs() }
            older.forEach { f ->
                Files.move(f.toPath(), File(archive, f.name).toPath(), StandardCopyOption.REPLACE_EXISTING)
                logger.info { "Archived ${f.name} (server has reset since)" }
            }
        }
    }
}
