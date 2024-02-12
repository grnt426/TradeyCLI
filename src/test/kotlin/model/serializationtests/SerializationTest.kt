package model.serializationtests

import model.Agent
import model.faction.Faction
import model.ShipyardResults
import model.contract.Contract
import model.ship.Ship
import model.system.System
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import java.io.File
import kotlin.test.Test

class SerializationTest {

    @Test
    fun `can read results from checking waypoint info of a shipyard`() {
        val file = File("src/test/resources/shipyard_result.json").readText()
        Json.decodeFromString<JsonObject>(file)["model"]?.let {
            Json.decodeFromJsonElement<ShipyardResults>(
                it
            )
        }!!
    }

    @Test
    fun `can read results when creating an agent`() {
        val file = File("src/test/resources/initial_agent_data.json").readText()
        val agentData = Json.decodeFromString<JsonObject>(file)["model"]?.jsonObject
        agentData?.get("agent")?.let { Json.decodeFromJsonElement<Agent>(it.jsonObject) }
        agentData?.get("faction")?.let { Json.decodeFromJsonElement<Faction>(it.jsonObject) }
        agentData?.get("ship")?.let { Json.decodeFromJsonElement<Ship>(it.jsonObject) }
        agentData?.get("contract")?.let { Json.decodeFromJsonElement<Contract>(it) }
    }

    @Test
    fun `can read response from systems`() {
        val file = File("src/test/resources/systems_result.json").readText()
        val system = Json.decodeFromString<System>(file)
    }
}