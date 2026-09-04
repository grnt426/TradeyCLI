package storage

import org.jetbrains.exposed.sql.Table

/** Free-form key/value: reset date, agent symbol, schema version. */
object MetaTable : Table("meta") {
    val key = varchar("key", 64)
    val value = text("value")
    override val primaryKey = PrimaryKey(key)
}

/**
 * One row per entity, the API's JSON as the payload plus the columns worth indexing. The JSON is
 * what the models decode, so a model change never needs a migration; only indexed columns do.
 */
abstract class EntityTable(name: String) : Table(name) {
    val symbol = varchar("symbol", 64)
    val json = text("json")
    val fetchedAt = long("fetched_at")
    override val primaryKey = PrimaryKey(symbol)
}

abstract class SystemScopedTable(name: String) : EntityTable(name) {
    val systemSymbol = varchar("system_symbol", 32).index()
}

object AgentTable : EntityTable("agent")
object ShipTable : EntityTable("ships")
object SystemTable : EntityTable("systems")
object WaypointTable : SystemScopedTable("waypoints")
object MarketTable : SystemScopedTable("markets")
object ShipyardTable : SystemScopedTable("shipyards")

/** Every observed market price, appended on each market fetch. The dataset with long-term value. */
object PriceTable : Table("market_prices") {
    val id = long("id").autoIncrement()
    val marketSymbol = varchar("market_symbol", 64).index()
    val tradeSymbol = varchar("trade_symbol", 64).index()
    val type = varchar("type", 16)
    val supply = varchar("supply", 16)
    val activity = varchar("activity", 16).nullable()
    val purchasePrice = integer("purchase_price")
    val sellPrice = integer("sell_price")
    val tradeVolume = integer("trade_volume")
    val observedAt = long("observed_at").index()
    override val primaryKey = PrimaryKey(id)
}

/** Our own buys and sells, as the API reports them. */
object TransactionTable : Table("transactions") {
    val id = long("id").autoIncrement()
    val shipSymbol = varchar("ship_symbol", 64).index()
    val waypointSymbol = varchar("waypoint_symbol", 64)
    val tradeSymbol = varchar("trade_symbol", 64)
    val type = varchar("type", 16)
    val units = integer("units")
    val pricePerUnit = integer("price_per_unit")
    val totalPrice = integer("total_price")
    val timestamp = varchar("timestamp", 40)
    override val primaryKey = PrimaryKey(id)
}

/** Where a behaviour is: its phase and detail, written at every status change; also the resume point. */
object CheckpointTable : Table("checkpoints") {
    val id = varchar("id", 64)
    val behaviour = varchar("behaviour", 64)
    val entity = varchar("entity", 64).nullable().index()
    val phase = varchar("phase", 64)
    val params = text("params")
    val detail = text("detail").nullable()
    val updatedAt = long("updated_at")
    override val primaryKey = PrimaryKey(id)
}

/** The bank after every change we caused: the credits graph and its trend. */
object CreditsTable : Table("credits_history") {
    val id = long("id").autoIncrement()
    val at = long("at").index()
    val credits = long("credits")
    override val primaryKey = PrimaryKey(id)
}

/** Every extraction we made: the dataset that calibrates the simulator's yield model. */
object ExtractionTable : Table("extractions") {
    val id = long("id").autoIncrement()
    val shipSymbol = varchar("ship_symbol", 64)
    val waypointSymbol = varchar("waypoint_symbol", 64).index()
    val tradeSymbol = varchar("trade_symbol", 64)
    val units = integer("units")
    val surveySignature = varchar("survey_signature", 128).nullable()
    val modifiers = varchar("modifiers", 200)
    val at = long("at").index()
    override val primaryKey = PrimaryKey(id)
}

/** One row per API attempt, for tuning the pacer and for blame when something goes wrong. */
object RequestLogTable : Table("request_log") {
    val id = long("id").autoIncrement()
    val at = long("at").index()
    val path = varchar("path", 200)
    val priority = varchar("priority", 16)
    val status = integer("status")
    val durationMs = long("duration_ms")
    val attempt = integer("attempt")
    override val primaryKey = PrimaryKey(id)
}

val ALL_TABLES = listOf(
    MetaTable, AgentTable, ShipTable, SystemTable, WaypointTable, MarketTable, ShipyardTable,
    PriceTable, TransactionTable, ExtractionTable, CheckpointTable, CreditsTable, RequestLogTable,
)
