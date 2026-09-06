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

/** Every agent on the server as last paged: their homes do not change within a reset. */
object PublicAgentTable : EntityTable("public_agents")

/** Jump gates read, keyed by the gate waypoint, with their connections; they do not change within a reset. */
object GateTable : EntityTable("gate_connections")
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
    /** The chain the ship was working, if any. */
    val chain = varchar("chain", 64).nullable().index()
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

/** Every contract seen, with what it paid and what fulfilling it cost us: do they grow in value? */
object ContractTable : Table("contracts") {
    val id = varchar("id", 64)
    val json = text("json")
    val faction = varchar("faction", 32)
    val type = varchar("type", 32)
    val tradeSymbol = varchar("trade_symbol", 64).nullable()
    val units = integer("units")
    val destination = varchar("destination", 64).nullable()
    val onAccepted = long("on_accepted")
    val onFulfilled = long("on_fulfilled")
    val cost = long("cost")
    val acceptedAt = long("accepted_at").nullable()
    val fulfilledAt = long("fulfilled_at").nullable()
    val seenAt = long("seen_at")
    override val primaryKey = PrimaryKey(id)
}

/** Every delivery to a construction site: with the gate-tagged purchases, the real cost curve. */
object SupplyTable : Table("construction_supplies") {
    val id = long("id").autoIncrement()
    val shipSymbol = varchar("ship_symbol", 64)
    val waypointSymbol = varchar("waypoint_symbol", 64).index()
    val tradeSymbol = varchar("trade_symbol", 64)
    val units = integer("units")
    val at = long("at").index()
    override val primaryKey = PrimaryKey(id)
}

/**
 * Credits that moved without a market transaction: ship purchases, chart rewards, contract
 * payments. With the tagged transactions this is the whole ledger: where money went and came from.
 */
object LedgerTable : Table("ledger") {
    val id = long("id").autoIncrement()
    val at = long("at").index()
    val shipSymbol = varchar("ship_symbol", 64)
    /** ships, chart, contract */
    val kind = varchar("kind", 32).index()
    /** Signed: income positive, spending negative. */
    val credits = long("credits")
    val note = varchar("note", 200)
    override val primaryKey = PrimaryKey(id)
}

/** Every phase change of every ship, so idle time can be measured: a ship with nothing to do is a plan that failed to find something. */
object PhaseLogTable : Table("phase_log") {
    val id = long("id").autoIncrement()
    val at = long("at").index()
    val shipSymbol = varchar("ship_symbol", 64).index()
    val behaviour = varchar("behaviour", 64)
    val phase = varchar("phase", 64)
    val detail = varchar("detail", 200)
    override val primaryKey = PrimaryKey(id)
}

/**
 * Every timed thing a ship does, by kind: cruise, drift, burn, extract, siphon, survey, jump. With
 * the phase log's idle time this is the ship's day, so "where does the time go" is a query.
 */
object ActivityTable : Table("activity_log") {
    val id = long("id").autoIncrement()
    val at = long("at").index()
    val shipSymbol = varchar("ship_symbol", 64).index()
    val behaviour = varchar("behaviour", 64)
    val kind = varchar("kind", 16).index()
    val detail = varchar("detail", 120)
    val seconds = long("seconds")
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

/** The public agent list sampled over time: credits and ship count per agent, so the leaders' rates can be compared with ours. */
object PublicAgentSampleTable : Table("public_agent_samples") {
    val id = long("id").autoIncrement()
    val at = long("at").index()
    val symbol = varchar("symbol", 64)
    val credits = long("credits")
    val ships = integer("ships")
    override val primaryKey = PrimaryKey(id)
}

val ALL_TABLES = listOf(
    PublicAgentTable, GateTable, PublicAgentSampleTable,
    MetaTable, AgentTable, ShipTable, SystemTable, WaypointTable, MarketTable, ShipyardTable,
    PriceTable, TransactionTable, ExtractionTable, CheckpointTable, CreditsTable, ContractTable, SupplyTable, RequestLogTable, LedgerTable, PhaseLogTable, ActivityTable,
)
