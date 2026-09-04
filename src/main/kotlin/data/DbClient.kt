package data

import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.statements.InsertStatement
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.timer

private val logger = KotlinLogging.logger {}
object DbClient {

    const val DATABASE_DIR = "database"

    private lateinit var db: Database
    private var connectedTo: String? = null
    val writeQueue = CopyOnWriteArrayList<() -> InsertStatement<*>>()

    /**
     * Connects to `database/<name>.db`, creating the folder and tables as needed, and starts the
     * background writer. Calling it again for the same database is a no-op, so the boot paths that
     * initialise twice do not end up with two writer timers.
     */
    fun createClient(databaseName: String = "tradey") {
        if (connectedTo == databaseName) {
            logger.debug { "DB client for '$databaseName' already created" }
            return
        }
        logger.debug { "Creating DB client for '$databaseName'" }
        File(DATABASE_DIR).mkdirs()
        db = Database.connect("jdbc:sqlite:./$DATABASE_DIR/$databaseName.db", "org.sqlite.JDBC")
        transaction { SchemaUtils.create(SavedScripts, PriceHistory) }
        connectedTo = databaseName

        timer("dbwriter", true, 0, 10) {
            transaction {
                writeQueue.forEach { t ->
                    t()
                    writeQueue.remove(t)
                }
            }
        }
        logger.info { "DB Client created" }
    }
}
