package model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import model.actions.Extraction
import model.actions.Yield
import model.contract.Contract
import model.contract.ContractTerms
import model.contract.DeliverTerm
import model.contract.PaymentTerm
import model.faction.Faction
import model.faction.FactionSymbol
import model.faction.FactionTrait
import model.faction.FactionTraitType
import model.market.ActivityLevel
import model.market.Market
import model.market.MarketTradeGood
import model.market.MarketTransaction
import model.market.ShipyardTransaction
import model.market.SupplyLevel
import model.market.TradeGood
import model.market.TradeGoodType
import model.market.TradeSymbol
import model.market.TransactionType
import model.ship.Cooldown
import model.ship.Crew
import model.ship.Navigation
import model.ship.PurchasableShip
import model.ship.Requirements
import model.ship.Route
import model.ship.Ship
import model.ship.ShipNavStatus
import model.ship.ShipRole
import model.ship.ShipType
import model.ship.components.Cargo
import model.ship.components.Engine
import model.ship.components.Frame
import model.ship.components.Fuel
import model.ship.components.Inventory
import model.ship.components.Module
import model.ship.components.Mount
import model.ship.components.MountType
import model.ship.components.Reactor
import model.ship.components.Registration
import model.system.Chart
import model.system.System
import model.system.SystemWaypoint
import model.system.Waypoint
import model.system.WaypointModifier
import model.system.WaypointOrbital
import model.system.WaypointType
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Compares the hand-written models with the cached live OpenAPI spec. Refresh the spec with
 * api-docs/refresh.ps1, run the build, and this test lists exactly what moved.
 *
 * A failure means a decode would fail against the real API: either the model requires a field the
 * spec does not define, or the spec can send an enum value the model does not know. Fields the
 * spec marks required but the model lacks are only reported, since they are merely ignored.
 */
class ModelDriftTest {

    private val schemas: JsonObject = Json.parseToJsonElement(File("api-docs/live/openapi-bundled.json").readText())
        .jsonObject.getValue("components").jsonObject.getValue("schemas").jsonObject

    private val objectSchemas: List<Pair<KSerializer<*>, String>> = listOf(
        Agent.serializer() to "Agent",
        Ship.serializer() to "Ship",
        Navigation.serializer() to "ShipNav",
        Route.serializer() to "ShipNavRoute",
        Location.serializer() to "ShipNavRouteWaypoint",
        Crew.serializer() to "ShipCrew",
        Fuel.serializer() to "ShipFuel",
        Cooldown.serializer() to "Cooldown",
        Frame.serializer() to "ShipFrame",
        Engine.serializer() to "ShipEngine",
        Reactor.serializer() to "ShipReactor",
        Module.serializer() to "ShipModule",
        Mount.serializer() to "ShipMount",
        Requirements.serializer() to "ShipRequirements",
        Registration.serializer() to "ShipRegistration",
        Cargo.serializer() to "ShipCargo",
        Inventory.serializer() to "ShipCargoItem",
        System.serializer() to "System",
        SystemWaypoint.serializer() to "SystemWaypoint",
        WaypointOrbital.serializer() to "WaypointOrbital",
        Waypoint.serializer() to "Waypoint",
        WaypointTrait.serializer() to "WaypointTrait",
        WaypointModifier.serializer() to "WaypointModifier",
        Chart.serializer() to "Chart",
        Faction.serializer() to "Faction",
        FactionTrait.serializer() to "FactionTrait",
        Market.serializer() to "Market",
        TradeGood.serializer() to "TradeGood",
        MarketTradeGood.serializer() to "MarketTradeGood",
        MarketTransaction.serializer() to "MarketTransaction",
        Shipyard.serializer() to "Shipyard",
        PurchasableShip.serializer() to "ShipyardShip",
        ShipyardTransaction.serializer() to "ShipyardTransaction",
        Contract.serializer() to "Contract",
        ContractTerms.serializer() to "ContractTerms",
        DeliverTerm.serializer() to "ContractDeliverGood",
        PaymentTerm.serializer() to "ContractPayment",
        Extraction.serializer() to "Extraction",
        Yield.serializer() to "ExtractionYield",
    )

    /** Enum serializer to the schema holding the values: a top-level enum schema or `Schema.property`. */
    private val enumSchemas: List<Pair<KSerializer<*>, String>> = listOf(
        serializer<TradeSymbol>() to "TradeSymbol",
        serializer<WaypointType>() to "WaypointType",
        serializer<WaypointTraitSymbol>() to "WaypointTraitSymbol",
        serializer<ShipType>() to "ShipType",
        serializer<ShipRole>() to "ShipRole",
        serializer<ShipNavStatus>() to "ShipNavStatus",
        serializer<FactionSymbol>() to "FactionSymbol",
        serializer<FactionTraitType>() to "FactionTraitSymbol",
        serializer<SupplyLevel>() to "SupplyLevel",
        serializer<ActivityLevel>() to "ActivityLevel",
        serializer<MountType>() to "ShipMount.symbol",
        serializer<TradeGoodType>() to "MarketTradeGood.type",
        serializer<TransactionType>() to "MarketTransaction.type",
    )

    @Test
    fun `every field a model requires exists in the spec`() {
        val failures = mutableListOf<String>()
        val notes = mutableListOf<String>()
        for ((serializer, schemaName) in objectSchemas) {
            val schema = schemas[schemaName]?.jsonObject ?: run { failures += "schema $schemaName not in spec"; continue }
            val specProps = schema["properties"]?.jsonObject?.keys ?: emptySet()
            val specRequired = schema["required"]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet() ?: emptySet()
            val descriptor = serializer.descriptor
            val modelName = descriptor.serialName.substringAfterLast('.')
            for (i in 0 until descriptor.elementsCount) {
                val field = descriptor.getElementName(i)
                val optional = descriptor.isElementOptional(i)
                when {
                    field in specProps && (optional || field in specRequired) -> Unit
                    field in specProps -> notes += "$modelName.$field is required by the model but optional in $schemaName"
                    optional -> notes += "$modelName.$field is not in $schemaName (harmless, has a default)"
                    else -> failures += "$modelName.$field is required by the model but $schemaName does not define it"
                }
            }
            specRequired.filterNot { it in descriptor.elementNames }.forEach {
                notes += "$schemaName.$it is required by the spec but $modelName does not model it"
            }
        }
        notes.forEach { println("drift note: $it") }
        assertTrue(failures.isEmpty(), failures.joinToString("\n", prefix = "Model fields the API will not send:\n"))
    }

    @Test
    fun `every enum value the spec can send is known to the model`() {
        val failures = mutableListOf<String>()
        for ((serializer, ref) in enumSchemas) {
            val specValues = enumValues(ref) ?: run { failures += "enum $ref not in spec"; continue }
            val modelValues = serializer.descriptor.elementNames.toSet()
            val missing = specValues - modelValues
            if (missing.isNotEmpty()) failures += "${serializer.descriptor.serialName.substringAfterLast('.')} lacks ${missing.sorted()} (from $ref)"
            (modelValues - specValues).takeIf { it.isNotEmpty() }?.let {
                println("drift note: ${serializer.descriptor.serialName.substringAfterLast('.')} has extra values ${it.sorted()}")
            }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n", prefix = "Enum values the API can send that the model cannot decode:\n"))
    }

    private fun enumValues(ref: String): Set<String>? {
        val (schemaName, property) = if ('.' in ref) ref.split('.', limit = 2) else listOf(ref, null)
        var node = schemas[schemaName]?.jsonObject ?: return null
        if (property != null) node = node["properties"]?.jsonObject?.get(property)?.jsonObject ?: return null
        return node["enum"]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet()
    }
}
