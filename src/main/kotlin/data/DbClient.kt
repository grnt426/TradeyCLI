package data

import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.statements.InsertStatement
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.timer

object DbClient {

    lateinit var db: Database
    val writeQueue = CopyOnWriteArrayList<() -> InsertStatement<*>>()

    fun createClient(databaseName: String = "tradey") {
        db = Database.connect("jdbc:sqlite:./database/$databaseName.db", "org.sqlite.JDBC")
        transaction { SchemaUtils.create(SavedScripts) }

        timer("dbwriter",true, 0, 10) {
            transaction {
                writeQueue.forEach { t ->
                    t()
                    writeQueue.remove(t)
                }
            }
        }
    }
}