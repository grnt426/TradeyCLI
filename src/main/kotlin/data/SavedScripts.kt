package data

import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.upsert

object SavedScripts: Table() {

    val id: Column<String> = varchar("id", 128)
    val scriptState: Column<String> = varchar("script_state", 128)
    val entityId: Column<String?> = varchar("entity_id", 128).nullable()
    val scriptType: Column<String> = varchar("script_type", 1024)

    override val primaryKey = PrimaryKey(id)

    fun saveState(uuid: String, currentState: String, heldEntity: String?, scriptName: String) {
        DbClient.writeQueue.add {
            SavedScripts.upsert { s ->
                s[id] = uuid
                s[scriptState] = currentState
                s[entityId] = heldEntity
                s[scriptType] = scriptName
            }
        }
    }
}