package data

import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction

object DbClient {

    lateinit var db: Database

    fun createClient(databaseName: String = "tradey") {
        db = Database.connect("jdbc:sqlite:./database/$databaseName.db", "org.sqlite.JDBC")
        transaction { SchemaUtils.create(SavedScripts) }
    }
}